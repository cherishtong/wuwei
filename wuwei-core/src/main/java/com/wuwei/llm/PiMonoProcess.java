package com.wuwei.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manages the Pi bun exe process lifecycle — start, monitor, auto-restart, stop.
 */
public class PiMonoProcess {

    private static final Logger log = LoggerFactory.getLogger(PiMonoProcess.class);

    private final String exePath;
    private Process process;
    private volatile boolean running = false;
    private volatile boolean shutdownRequested = false;

    public PiMonoProcess(String exePath) {
        this.exePath = exePath;
    }

    public void start() throws IOException {
        shutdownRequested = false;

        Path exe = Path.of(exePath);
        if (!Files.exists(exe)) {
            // During development, the exe may not exist yet — log warning and continue
            // The Tauri sidecar will bring its own binary.
            log.warn("Pi exe not found at {}, will attempt to start anyway", exePath);
        }

        log.info("Starting Pi process: {}", exePath);
        process = new ProcessBuilder(exePath)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();

        running = true;

        // Monitor process in a virtual thread, auto-restart on crash
        Thread.ofVirtual().start(() -> {
            try {
                int exitCode = process.waitFor();
                running = false;
                if (!shutdownRequested) {
                    log.error("Pi process exited unexpectedly (code {}), restarting in 3s...", exitCode);
                    Thread.sleep(3000);
                    start();
                } else {
                    log.info("Pi process exited with code {}", exitCode);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Pi restart failed: {}", e.getMessage());
            }
        });

        log.info("Pi process started, PID: {}", process.pid());
    }

    public void stop() {
        shutdownRequested = true;
        if (process != null && process.isAlive()) {
            process.destroy();
            log.info("Pi process stopped");
        }
        running = false;
    }

    public InputStream getStdout() {
        if (process == null) throw new IllegalStateException("Pi process not started");
        return process.getInputStream();
    }

    public OutputStream getStdin() {
        if (process == null) throw new IllegalStateException("Pi process not started");
        return process.getOutputStream();
    }

    public boolean isRunning() {
        return running && process != null && process.isAlive();
    }
}
