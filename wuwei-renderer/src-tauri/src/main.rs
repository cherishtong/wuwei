#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::io::{BufRead, BufReader};
use std::os::windows::process::CommandExt;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Manager, State};

struct KernelState {
    child: Mutex<Option<Child>>,
    port: Mutex<Option<u16>>,
}

#[tauri::command]
fn get_kernel_port(state: State<KernelState>) -> Result<u16, String> {
    state
        .port
        .lock()
        .map_err(|e| e.to_string())?
        .ok_or_else(|| "kernel not ready".to_string())
}

#[tauri::command]
fn pick_folder() -> Result<String, String> {
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
        if path == "__CANCELLED__" {
            Ok(String::new()) // user cancelled — empty string
        } else if path.is_empty() {
            Err("no folder selected".to_string())
        } else {
            Ok(path)
        }
    } else {
        Err("folder picker failed".to_string())
    }
}

fn find_kernel(app: &AppHandle) -> Option<std::path::PathBuf> {
    // Dev mode: native build path
    let dev_exe = std::path::PathBuf::from(
        "../../wuwei-core/build/native/nativeCompile/wuwei-kernel.exe",
    );
    if dev_exe.exists() {
        return Some(dev_exe);
    }

    // Production: look in Tauri resource directory
    if let Ok(resource_dir) = app.path().resource_dir() {
        let candidates = [
            resource_dir.join("binaries").join("wuwei-kernel.exe"),
            resource_dir.join("kernel").join("wuwei-kernel.exe"),
            resource_dir.join("kernel"),
        ];
        for exe in &candidates {
            if exe.exists() {
                return Some(exe.clone());
            }
        }
    }

    None
}

fn find_config(exe_path: &std::path::Path) -> Option<std::path::PathBuf> {
    // Look for wuwei.json alongside the kernel binary and in ~/.wuwei/
    let candidates: &[fn(&std::path::Path) -> Option<std::path::PathBuf>] = &[
        // Same directory as kernel
        |p: &std::path::Path| {
            let c = p.parent()?.join("wuwei.json");
            c.exists().then_some(c)
        },
        // Four levels up (wuwei-core/build/native/nativeCompile -> wuwei-core)
        |p: &std::path::Path| {
            let c = p.parent()?.parent()?.parent()?.parent()?.join("wuwei.json");
            c.exists().then_some(c)
        },
        // Home directory
        |_: &std::path::Path| {
            let home = std::env::var("USERPROFILE").ok()?;
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

fn spawn_kernel(app: &AppHandle) {
    let exe_path = match find_kernel(app) {
        Some(p) => p,
        None => {
            eprintln!("[kernel] FATAL: wuwei-kernel.exe not found");
            return;
        }
    };

    eprintln!("[kernel] Starting: {}", exe_path.display());

    let mut cmd = Command::new(exe_path.to_str().unwrap());
    cmd.stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .creation_flags(0x08000000); // CREATE_NO_WINDOW

    if let Some(config_path) = find_config(&exe_path) {
        eprintln!("[kernel] Config: {}", config_path.display());
        cmd.arg("--config").arg(config_path.to_str().unwrap());
    }

    let mut child = match cmd.spawn()
    {
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
                    if text.starts_with("WUWEI_PORT:") {
                        if let Ok(port) = text.trim_start_matches("WUWEI_PORT:").parse::<u16>() {
                            let state = app_stdout.state::<KernelState>();
                            *state.port.lock().unwrap() = Some(port);
                            let _ = app_stdout.emit("kernel-ready", port);
                            eprintln!("[kernel] Ready on port {}", port);
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
                        // No child — was killed or never started
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
                // Give kernel time to start
                std::thread::sleep(std::time::Duration::from_secs(3));
            }
        }
    });
}

fn graceful_kill(state: &KernelState) {
    if let Some(mut child) = state.child.lock().unwrap().take() {
        eprintln!("[kernel] Shutting down kernel...");
        // On Windows, kill() is immediate. Try to let it exit first.
        let pid = child.id();
        // Send graceful terminate (no /F flag)
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
        // Force kill
        eprintln!("[kernel] Force killing kernel...");
        let _ = child.kill();
        let _ = child.wait();
        eprintln!("[kernel] kernel process terminated");
    }
}

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
