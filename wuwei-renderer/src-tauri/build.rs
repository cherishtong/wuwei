fn wait_for_kernel() {
    let kernel = std::path::PathBuf::from(
        "../../wuwei-core/build/native/nativeCompile/wuwei-kernel.exe",
    );
    if !kernel.exists() {
        return;
    }
    for i in 0..30 {
        match std::fs::metadata(&kernel) {
            Ok(_) => {
                // Also try to open it exclusively to ensure it's fully released
                if std::fs::OpenOptions::new().read(true).open(&kernel).is_ok() {
                    return;
                }
            }
            Err(e) => {
                eprintln!(
                    "cargo:warning=kernel.exe not accessible (attempt {}): {}",
                    i + 1,
                    e
                );
            }
        }
        std::thread::sleep(std::time::Duration::from_secs(2));
    }
    eprintln!("cargo:warning=kernel.exe still locked after 30 retries, proceeding anyway");
}

fn main() {
    wait_for_kernel();
    tauri_build::build()
}
