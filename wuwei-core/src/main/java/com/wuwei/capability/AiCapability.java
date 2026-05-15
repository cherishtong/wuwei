package com.wuwei.capability;

import com.wuwei.llm.LlmClient;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * AI/LLM capability for Skills.
 * Exposes {@code capability.ai.ask(prompt)} → {status, body}
 * and {@code capability.ai.askStream(prompt, onChunk, onDone)} for streaming.
 *
 * Streaming uses per-skill {@link Consumer<Runnable>} enqueue functions
 * registered by each SkillRuntime to ensure JS callbacks execute on
 * the correct single-threaded event loop.
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

    /** Per-skill enqueue functions for streaming callbacks. */
    private final ConcurrentHashMap<String, Consumer<Runnable>> enqueues = new ConcurrentHashMap<>();

    public AiCapability(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** Register the skill's event-loop enqueue function. Called by SkillRuntime. */
    public void registerEnqueue(String skillId, Consumer<Runnable> enqueue) {
        enqueues.put(skillId, enqueue);
        log.info("ai stream enqueue registered for {}", skillId);
    }

    /** Remove the skill's enqueue on unload. */
    public void unregisterEnqueue(String skillId) {
        enqueues.remove(skillId);
        log.info("ai stream enqueue unregistered for {}", skillId);
    }

    public ProxyObject forSkill(String skillId) {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                return switch (key) {
                    case "ask" -> (ProxyExecutable) args ->
                        executeAsk(args[0].asString());
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

    Object executeAsk(String prompt) {
        try {
            log.info("ai.ask: {}...", prompt.length() > 80 ? prompt.substring(0, 80) : prompt);
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
        Value onChunk = args[1];                 // function(chunkText)
        Value onDone = args.length > 2 ? args[2] : null; // optional function()

        if (!onChunk.canExecute()) {
            log.warn("askStream: onChunk is not executable for {}", skillId);
            return null;
        }

        Consumer<Runnable> enqueue = enqueues.get(skillId);
        if (enqueue == null) {
            log.warn("askStream: no enqueue registered for {} — stream not started", skillId);
            // Fall back to synchronous ask — deliver full body as one chunk
            Object syncResult = executeAsk(prompt);
            if (syncResult instanceof ProxyObject po) {
                Object body = po.getMember("body");
                if (body instanceof String s) {
                    try { onChunk.execute(s); }
                    catch (Exception e) { log.warn("onChunk error: {}", e.getMessage()); }
                }
            }
            if (onDone != null && onDone.canExecute()) {
                try { onDone.execute(); }
                catch (Exception e) { log.warn("onDone error: {}", e.getMessage()); }
            }
            return null;
        }

        Thread streamThread = new Thread(() -> {
            try {
                llmClient.chatStream(AI_SYSTEM_PROMPT, prompt,
                    text -> enqueue.accept(() -> {
                        try { onChunk.execute(text); }
                        catch (Exception e) { log.warn("onChunk error: {}", e.getMessage()); }
                    }),
                    () -> {
                        if (onDone != null && onDone.canExecute()) {
                            enqueue.accept(() -> {
                                try { onDone.execute(); }
                                catch (Exception e) { log.warn("onDone error: {}", e.getMessage()); }
                            });
                        }
                    },
                    err -> enqueue.accept(() -> {
                        try { onChunk.execute("ERROR: " + err); }
                        catch (Exception e) { log.warn("onChunk(error) error: {}", e.getMessage()); }
                    })
                );
            } catch (Exception e) {
                log.error("askStream background thread error for {}: {}", skillId, e.getMessage());
            }
        }, "ai-stream-" + skillId);
        streamThread.setDaemon(true);
        streamThread.start();

        return null; // Returns immediately — streaming continues via callbacks
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
