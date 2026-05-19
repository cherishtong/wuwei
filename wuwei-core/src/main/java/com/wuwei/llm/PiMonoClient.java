package com.wuwei.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.bus.EventBus;
import com.wuwei.bus.event.KernelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * JSON-RPC 2.0 client that communicates with the Pi process over stdio.
 * Sends requests, reads responses on a virtual thread, matches by id.
 */
public class PiMonoClient {

    private static final Logger log = LoggerFactory.getLogger(PiMonoClient.class);

    private final PiMonoProcess piProcess;
    private final EventBus eventBus;
    private final ObjectMapper mapper;

    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Consumer<ProgressNotification>> progressCallbacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Consumer<String>> streamCallbacks = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);

    public PiMonoClient(PiMonoProcess piProcess, EventBus eventBus, ObjectMapper mapper) {
        this.piProcess = piProcess;
        this.eventBus = eventBus;
        this.mapper = mapper;
    }

    /** Start the read loop on a virtual thread. Called after Pi process starts. */
    public void startReadLoop() {
        Thread.ofVirtual().start(this::readLoop);
    }

    /**
     * Send a JSON-RPC request and return a future for the result.
     *
     * @param method        JSON-RPC method name
     * @param params        Request params (will be serialized to JSON)
     * @param onProgress    Optional callback for skill/progress notifications
     * @param onStreamToken Optional callback for ai/streamToken notifications
     * @return Future that completes with the result JsonNode
     */
    public CompletableFuture<JsonNode> call(
            String method,
            Object params,
            Consumer<ProgressNotification> onProgress,
            Consumer<String> onStreamToken) {

        String id = "req-" + idCounter.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        if (onProgress != null) progressCallbacks.put(id, onProgress);
        if (onStreamToken != null) streamCallbacks.put(id, onStreamToken);

        send(Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "method", method,
            "params", params
        ));

        // Timeout after 60 seconds
        future.orTimeout(60, TimeUnit.SECONDS)
              .exceptionally(ex -> {
                  cleanup(id);
                  return null;
              });

        return future;
    }

    // ── Read loop ─────────────────────────────────────────────────

    private void readLoop() {
        try (var reader = new BufferedReader(
                new InputStreamReader(piProcess.getStdout(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    handleMessage(mapper.readTree(line));
                } catch (Exception e) {
                    log.warn("Failed to parse Pi message: {}", line.length() > 100
                        ? line.substring(0, 100) + "..." : line);
                }
            }
        } catch (IOException e) {
            log.error("Pi read loop exception: {}", e.getMessage());
            if (!piProcess.isRunning()) {
                log.warn("Pi process is not running, read loop exiting");
            }
        }
    }

    private void handleMessage(JsonNode msg) {
        if (msg.has("id") && !msg.get("id").isNull()) {
            // Response — resolve the pending future
            String id = msg.get("id").asText();
            CompletableFuture<JsonNode> future = pending.remove(id);

            if (future == null) {
                log.debug("No pending future for id: {}", id);
                return;
            }

            if (msg.has("error")) {
                JsonNode error = msg.get("error");
                future.completeExceptionally(new PiMonoException(
                    error.get("code").asInt(),
                    error.get("message").asText()));
            } else {
                future.complete(msg.get("result"));
            }

            cleanup(id);

        } else if (msg.has("method")) {
            // Notification — dispatch
            String method = msg.get("method").asText();
            JsonNode params = msg.get("params");

            switch (method) {
                case "skill/progress"  -> handleProgress(params);
                case "pi/log"          -> handleLog(params);
                case "ai/streamToken"  -> handleStreamToken(params);
                default                -> log.debug("Unknown notification: {}", method);
            }
        }
    }

    // ── Notification handlers ─────────────────────────────────────

    private void handleProgress(JsonNode params) {
        String reqId = params.has("requestId") ? params.get("requestId").asText() : "";
        String message = params.get("message").asText();
        int percent = params.has("percent") ? params.get("percent").asInt() : -1;

        Consumer<ProgressNotification> cb = progressCallbacks.get(reqId);
        if (cb != null) {
            cb.accept(new ProgressNotification(reqId, message, percent));
        }

        eventBus.publish(new KernelEvent.PlanStep("generating", message));
    }

    private void handleLog(JsonNode params) {
        String level = params.get("level").asText();
        String message = params.get("message").asText();
        JsonNode data = params.has("data") && !params.get("data").isNull()
            ? params.get("data") : null;
        long timestamp = params.get("timestamp").asLong();

        // Forward to frontend as PiLog event
        eventBus.publish(new KernelEvent.PiLog(
            level, message,
            data != null ? data.toString() : null,
            timestamp));

        // Also log
        switch (level) {
            case "error" -> log.error("[pi] {}", message);
            case "warn"  -> log.warn("[pi] {}", message);
            case "debug" -> log.debug("[pi] {}", message);
            default      -> log.info("[pi] {}", message);
        }
    }

    private void handleStreamToken(JsonNode params) {
        String reqId = params.has("requestId") ? params.get("requestId").asText() : "";
        boolean done = params.has("done") && params.get("done").asBoolean();

        Consumer<String> cb = streamCallbacks.get(reqId);
        if (cb != null && !done) {
            cb.accept(params.get("token").asText());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private void send(Object msg) {
        try {
            String json = mapper.writeValueAsString(msg) + "\n";
            OutputStream stdin = piProcess.getStdin();
            synchronized (stdin) {
                stdin.write(json.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
        } catch (IOException e) {
            log.error("Failed to send message to Pi: {}", e.getMessage());
        }
    }

    private void cleanup(String id) {
        pending.remove(id);
        progressCallbacks.remove(id);
        streamCallbacks.remove(id);
    }
}
