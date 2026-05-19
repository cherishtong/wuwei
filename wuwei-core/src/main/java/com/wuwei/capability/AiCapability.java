package com.wuwei.capability;

import com.wuwei.llm.LlmClient;
import com.wuwei.llm.PiMonoAdapter;
import com.wuwei.llm.PiMonoException;
import com.wuwei.store.StoreService;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AI/LLM capability for Skills.
 * Exposes {@code capability.ai.ask(prompt)} → {status, body}
 * and {@code capability.ai.askStream(prompt, onChunk, onDone)} for streaming.
 *
 * Uses PiMonoAdapter as primary path; falls back to built-in LlmClient.
 */
public class AiCapability {

    private static final Logger log = LoggerFactory.getLogger(AiCapability.class);

    private static final String AI_SYSTEM_PROMPT = """
        You are a data retrieval assistant embedded in the Wuwei platform.
        Your response must be pure data — no markdown, no code fences, no explanations.
        If asked for structured data, return valid JSON.
        If asked for text, return plain text.
        Keep responses concise and directly usable by a script.
        """;

    private final LlmClient llmClient;
    private final PiMonoAdapter piAdapter;
    private final StoreService storeService;

    private final ConcurrentHashMap<String, Consumer<Runnable>> enqueues = new ConcurrentHashMap<>();

    public AiCapability(LlmClient llmClient, PiMonoAdapter piAdapter, StoreService storeService) {
        this.llmClient = llmClient;
        this.piAdapter = piAdapter;
        this.storeService = storeService;
    }

    public void registerEnqueue(String skillId, Consumer<Runnable> enqueue) {
        enqueues.put(skillId, enqueue);
    }

    public void unregisterEnqueue(String skillId) {
        enqueues.remove(skillId);
    }

    public ProxyObject forSkill(String skillId) {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                return switch (key) {
                    case "ask" -> (ProxyExecutable) args ->
                        executeAsk(skillId, args[0].asString());
                    case "askStream" -> (ProxyExecutable) args ->
                        executeAskStream(skillId, args);
                    default -> null;
                };
            }

            @Override
            public boolean hasMember(String key) {
                return Set.of("ask", "askStream").contains(key);
            }

            @Override
            public Set<String> getMemberKeys() { return Set.of("ask", "askStream"); }

            @Override
            public void putMember(String key, Value value) {}

            @Override
            public boolean removeMember(String key) { return false; }
        };
    }

    // ── Synchronous ask ───────────────────────────────────────────

    public Object executeAsk(String skillId, String prompt) {
        log.info("ai.ask [{}]: {}...", skillId,
            prompt.length() > 80 ? prompt.substring(0, 80) : prompt);

        // Try PI first
        if (piAdapter != null) {
            try {
                Map<String, String> model = storeService.getModelRouting("ai/ask");
                PiMonoAdapter.AiResult result = piAdapter.aiAsk(skillId, prompt, model);
                return responseProxy(result.status(), result.body());
            } catch (PiMonoException e) {
                log.warn("PI ai/ask failed, falling back to LlmClient: {}", e.getMessage());
            }
        }

        // Fallback
        try {
            String body = llmClient.chatSimple(AI_SYSTEM_PROMPT, prompt);
            return responseProxy(200, body);
        } catch (Exception e) {
            log.error("ai.ask failed: {}", e.getMessage());
            return responseProxy(-1, "AI 调用失败: " + e.getMessage());
        }
    }

    // ── Streaming ask ─────────────────────────────────────────────

    private Object executeAskStream(String skillId, Value[] args) {
        String prompt = args[0].asString();
        Value onChunk = args[1];
        Value onDone = args.length > 2 ? args[2] : null;

        if (!onChunk.canExecute()) {
            log.warn("askStream: onChunk is not executable for {}", skillId);
            return null;
        }

        Consumer<Runnable> enqueue = enqueues.get(skillId);
        if (enqueue == null) {
            log.warn("askStream: no enqueue registered for {}", skillId);
            Object syncResult = executeAsk(skillId, prompt);
            if (syncResult instanceof ProxyObject po) {
                Object body = po.getMember("body");
                if (body instanceof String s) {
                    try { onChunk.execute(s); }
                    catch (Exception ex) { log.warn("onChunk error: {}", ex.getMessage()); }
                }
            }
            if (onDone != null && onDone.canExecute()) {
                try { onDone.execute(); }
                catch (Exception ex) { log.warn("onDone error: {}", ex.getMessage()); }
            }
            return null;
        }

        // Try PI streaming first
        if (piAdapter != null) {
            Map<String, String> model = storeService.getModelRouting("ai/ask");
            piAdapter.aiAskStream(skillId, prompt, model,
                text -> enqueue.accept(() -> {
                    try { onChunk.execute(text); }
                    catch (Exception ex) { log.warn("onChunk error: {}", ex.getMessage()); }
                }),
                () -> {
                    if (onDone != null && onDone.canExecute()) {
                        enqueue.accept(() -> {
                            try { onDone.execute(); }
                            catch (Exception ex) { log.warn("onDone error: {}", ex.getMessage()); }
                        });
                    }
                }
            );
            return null;
        }

        // Fallback to LlmClient streaming
        Thread streamThread = new Thread(() -> {
            try {
                llmClient.chatStream(AI_SYSTEM_PROMPT, prompt,
                    text -> enqueue.accept(() -> {
                        try { onChunk.execute(text); }
                        catch (Exception ex) { log.warn("onChunk error: {}", ex.getMessage()); }
                    }),
                    () -> {
                        if (onDone != null && onDone.canExecute()) {
                            enqueue.accept(() -> {
                                try { onDone.execute(); }
                                catch (Exception ex) { log.warn("onDone error: {}", ex.getMessage()); }
                            });
                        }
                    },
                    err -> enqueue.accept(() -> {
                        try { onChunk.execute("ERROR: " + err); }
                        catch (Exception ex) { log.warn("onChunk(error) error: {}", ex.getMessage()); }
                    })
                );
            } catch (Exception e) {
                log.error("askStream background thread error for {}: {}", skillId, e.getMessage());
            }
        }, "ai-stream-" + skillId);
        streamThread.setDaemon(true);
        streamThread.start();

        return null;
    }

    // ── Response proxy factory ────────────────────────────────────

    private static ProxyObject responseProxy(int status, String body) {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                return switch (key) {
                    case "status" -> status;
                    case "body" -> body;
                    default -> null;
                };
            }
            @Override
            public boolean hasMember(String key) {
                return Set.of("status", "body").contains(key);
            }
            @Override
            public Set<String> getMemberKeys() { return Set.of("status", "body"); }
            @Override
            public void putMember(String key, Value value) {}
            @Override
            public boolean removeMember(String key) { return false; }
        };
    }
}
