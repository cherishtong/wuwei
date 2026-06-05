#![cfg_attr(all(not(debug_assertions), target_os = "windows"), windows_subsystem = "windows")]

use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Manager, State};

#[cfg(target_os = "windows")]
use std::os::windows::process::CommandExt;

struct KernelState {
    child: Mutex<Option<Child>>,
    port: Mutex<Option<u16>>,
}

// ── Tauri commands ──────────────────────────────────────────────

#[tauri::command]
fn get_kernel_port(state: State<KernelState>) -> Result<u16, String> {
    state
        .port
        .lock()
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "kernel not ready".to_string())
}

/// Cross-platform folder picker.
#[tauri::command]
fn pick_folder() -> Result<String, String> {
    #[cfg(target_os = "windows")]
    {
        let output = Command::new("powershell.exe")
            .args(&["-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Windows.Forms; \
                 $d = New-Object System.Windows.Forms.FolderBrowserDialog; \
                 $d.Description = '选择 Skill 文件夹'; \
                 if ($d.ShowDialog() -eq 'OK') { Write-Output $d.SelectedPath } else { Write-Output '__CANCELLED__' }"])
            .creation_flags(0x08000000)
            .output()
            .map_err(|e| e.to_string())?;

        if output.status.success() {
            let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if path == "__CANCELLED__" { return Ok(String::new()); }
            if path.is_empty() { return Err("no folder selected".to_string()); }
            return Ok(path);
        }
        return Err("folder picker failed".to_string());
    }

    #[cfg(target_os = "macos")]
    {
        let output = Command::new("osascript")
            .args(&["-e", r#"POSIX path of (choose folder with prompt "选择 Skill 文件夹")"#])
            .output()
            .map_err(|e| e.to_string())?;
        if output.status.success() {
            let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if path.is_empty() { return Ok(String::new()); }
            return Ok(path);
        }
        // User cancelled or error — return empty string
        return Ok(String::new());
    }

    #[cfg(target_os = "linux")]
    {
        // Try zenity first, then kdialog, then fallback
        for (cmd, args) in &[
            ("zenity", &["--file-selection", "--directory", "--title=选择 Skill 文件夹"][..]),
            ("kdialog", &["--getexistingdirectory", ""][..]),
        ] {
            if let Ok(output) = Command::new(cmd).args(args).output() {
                if output.status.success() {
                    let path = String::from_utf8_lossy(&output.stdout).trim().to_string();
                    if !path.is_empty() { return Ok(path); }
                }
            }
        }
        return Err("no folder picker available (install zenity or kdialog)".to_string());
    }

    // Unsupported platform fallback (shouldn't happen with Tauri targets)
    #[allow(unreachable_code)]
    Err("folder picker not supported on this platform".to_string())
}

// ── Kernel discovery ────────────────────────────────────────────

/// Platform-specific kernel binary name.
fn kernel_binary_name() -> &'static str {
    #[cfg(target_os = "windows")]
    { "wuwei-kernel.exe" }
    #[cfg(not(target_os = "windows"))]
    { "wuwei-kernel" }
}

fn find_kernel(app: &AppHandle) -> Option<std::path::PathBuf> {
    // Dev mode: native build path (relative to src-tauri)
    let dev_path = {
        let mut p = std::path::PathBuf::from("../../wuwei-core/build/native/nativeCompile");
        p.push(kernel_binary_name());
        p
    };
    if dev_path.exists() {
        return Some(dev_path);
    }

    // Production: look in Tauri resource directory
    if let Ok(resource_dir) = app.path().resource_dir() {
        let candidates = [
            resource_dir.join("binaries").join(kernel_binary_name()),
            resource_dir.join("kernel").join(kernel_binary_name()),
            resource_dir.join("kernel"), // bare name fallback
        ];
        for exe in &candidates {
            if exe.exists() {
                return Some(exe.clone());
            }
        }
    }

    None
}

// ── Config discovery ────────────────────────────────────────────

fn home_dir() -> Option<String> {
    #[cfg(target_os = "windows")]
    { std::env::var("USERPROFILE").ok() }
    #[cfg(not(target_os = "windows"))]
    { std::env::var("HOME").ok() }
}

fn find_config(exe_path: &std::path::Path) -> Option<std::path::PathBuf> {
    let candidates: &[fn(&std::path::Path) -> Option<std::path::PathBuf>] = &[
        // Same directory as kernel
        |p: &std::path::Path| {
            let c = p.parent()?.join("wuwei.json");
            c.exists().then_some(c)
        },
        // Four levels up (wuwei-core/build/native/nativeCompile → project root)
        |p: &std::path::Path| {
            let c = p.parent()?.parent()?.parent()?.parent()?.join("wuwei.json");
            c.exists().then_some(c)
        },
        // Home directory
        |_: &std::path::Path| {
            let home = home_dir()?;
            let c = std::path::PathBuf::from(home).join(".wuwei").join("wuwei.json");
            c.exists().then_some(c)
        },
    ];
    for f in candidates {
        if let Some(c) = f(exe_path) {
            return Some(c);
        }
    }
    None
}

// ── Kernel lifecycle ────────────────────────────────────────────

fn spawn_kernel(app: &AppHandle) {
    let exe_path = match find_kernel(app) {
        Some(p) => p,
        None => {
            eprintln!("[kernel] FATAL: {} not found", kernel_binary_name());
            return;
        }
    };

    eprintln!("[kernel] Starting: {}", exe_path.display());

    let mut cmd = Command::new(&exe_path);
    cmd.stdout(Stdio::piped()).stderr(Stdio::piped());

    #[cfg(target_os = "windows")]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW

    if let Some(config_path) = find_config(&exe_path) {
        eprintln!("[kernel] Config: {}", config_path.display());
        cmd.arg("--config").arg(&config_path);
    }

    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("[kernel] Failed to spawn kernel: {}", e);
            return;
        }
    };

    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();

    // Store child process
    {
        let state = app.state::<KernelState>();
        *state.child.lock().unwrap() = Some(child);
    }

    // Read stdout in background thread
    let app_stdout = app.clone();
    std::thread::spawn(move || {
        let reader = BufReader::new(stdout);
        for line in reader.lines() {
            match line {
                Ok(text) => {
                    println!("[kernel] {}", text);
                    if let Some(port_str) = text.strip_prefix("WUWEI_PORT:") {
                        if let Ok(port) = port_str.parse::<u16>() {
                            let state = app_stdout.state::<KernelState>();
                            *state.port.lock().unwrap() = Some(port);
                            let _ = app_stdout.emit("kernel-ready", port);
                            eprintln!("[kernel] Ready on port {}", port);
                        }
                    } else if let Some(json_str) = text.strip_prefix("FORWARD:") {
                        if let Ok(payload) = serde_json::from_str::<serde_json::Value>(json_str) {
                            let _ = app_stdout.emit("kernel-event", &payload);
                        }
                    }
                }
                Err(_) => break,
            }
        }
        eprintln!("[kernel] stdout stream ended");
    });

    // Read stderr in background thread
    std::thread::spawn(move || {
        let reader = BufReader::new(stderr);
        for line in reader.lines() {
            match line {
                Ok(text) => eprintln!("[kernel-err] {}", text),
                Err(_) => break,
            }
        }
    });
}

// ── Health monitor ──────────────────────────────────────────────

fn kernel_monitor(app: AppHandle) {
    std::thread::spawn(move || {
        // Give kernel time to start initially
        std::thread::sleep(std::time::Duration::from_secs(3));

        loop {
            std::thread::sleep(std::time::Duration::from_secs(2));

            let needs_restart = {
                let state = app.state::<KernelState>();
                let mut child_guard = state.child.lock().unwrap();
                match child_guard.as_mut() {
                    None => {
                        let port = *state.port.lock().unwrap();
                        port.is_none() // restart if port was never acquired
                    }
                    Some(child) => match child.try_wait() {
                        Ok(Some(status)) => {
                            eprintln!("[kernel] Process exited with {:?}, restarting...", status);
                            *state.port.lock().unwrap() = None;
                            let _ = app.emit("kernel-exited", ());
                            true
                        }
                        Ok(None) => false, // still running
                        Err(e) => {
                            eprintln!("[kernel] Error checking process: {}", e);
                            *state.port.lock().unwrap() = None;
                            true
                        }
                    },
                }
            };

            if needs_restart {
                spawn_kernel(&app);
                std::thread::sleep(std::time::Duration::from_secs(3));
            }
        }
    });
}

// ── Graceful shutdown ───────────────────────────────────────────

#[cfg(target_os = "windows")]
fn graceful_kill(state: &KernelState) {
    if let Some(mut child) = state.child.lock().unwrap().take() {
        eprintln!("[kernel] Shutting down kernel...");
        let pid = child.id();
        // Graceful terminate (no /F flag)
        let _ = Command::new("taskkill")
            .args(&["/PID", &pid.to_string()])
            .creation_flags(0x08000000)
            .spawn();
        // Wait up to 5 seconds
        for _ in 0..10 {
            match child.try_wait() {
                Ok(Some(status)) => {
                    eprintln!("[kernel] kernel exited with {:?}", status);
                    return;
                }
                Ok(None) => {
                    std::thread::sleep(std::time::Duration::from_millis(500));
                }
                Err(_) => break,
            }
        }
        eprintln!("[kernel] Force killing kernel...");
        let _ = child.kill();
        let _ = child.wait();
        eprintln!("[kernel] kernel process terminated");
    }
}

#[cfg(not(target_os = "windows"))]
fn graceful_kill(state: &KernelState) {
    if let Some(mut child) = state.child.lock().unwrap().take() {
        eprintln!("[kernel] Shutting down kernel (SIGTERM)...");
        let pid = child.id() as i32;

        // Send SIGTERM
        unsafe { libc::kill(pid, libc::SIGTERM); }

        // Wait up to 5 seconds for graceful exit
        for _ in 0..10 {
            match child.try_wait() {
                Ok(Some(status)) => {
                    eprintln!("[kernel] kernel exited with {:?}", status);
                    return;
                }
                Ok(None) => {
                    std::thread::sleep(std::time::Duration::from_millis(500));
                }
                Err(_) => break,
            }
        }
        // Force SIGKILL
        eprintln!("[kernel] Force killing kernel (SIGKILL)...");
        unsafe { libc::kill(pid, libc::SIGKILL); }
        let _ = child.wait();
        eprintln!("[kernel] kernel process terminated");
    }
}

// ── Entry point ─────────────────────────────────────────────────

fn main() {
    tauri::Builder::default()
        .manage(KernelState {
            child: Mutex::new(None),
            port: Mutex::new(None),
        })
        .setup(|app| {
            let handle = app.handle().clone();
            spawn_kernel(&handle);
            kernel_monitor(handle);
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                let state = window.state::<KernelState>();
                graceful_kill(&state);
            }
        })
        .invoke_handler(tauri::generate_handler![get_kernel_port, pick_folder])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
