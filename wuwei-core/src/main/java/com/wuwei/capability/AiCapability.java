package com.wuwei.capability;

import com.wuwei.llm.AgentFactory;
import com.wuwei.llm.AiAskAgent;
import com.wuwei.llm.AiResult;
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
 * AI/LLM capability for Skills via LangChain4j AiServices + ChatMemory.
 * Exposes {@code capability.ai.ask(prompt)} → {status, body}
 * and {@code capability.ai.askStream(prompt, onChunk, onDone)} for streaming.
 */
public class AiCapability {

    private static final Logger log = LoggerFactory.getLogger(AiCapability.class);

    private final AgentFactory agentFactory;
    private final StoreService storeService;

    private final ConcurrentHashMap<String, Consumer<Runnable>> enqueues = new ConcurrentHashMap<>();

    public AiCapability(AgentFactory agentFactory, StoreService storeService) {
        this.agentFactory = agentFactory;
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

        if (agentFactory == null) {
            log.warn("AgentFactory not available for ai.ask [{}]", skillId);
            return responseProxy(-1, "LLM 服务未初始化");
        }

        try {
            Map<String, String> routing = storeService.getModelRouting("ai/ask");
            AiAskAgent agent = agentFactory.createAskAgent(routing);
            String result = agent.ask(prompt);
            return responseProxy(200, result != null ? result : "");
        } catch (Exception e) {
            log.error("LLM ai/ask failed for {}: {}", skillId, e.getMessage());
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
            // Fallback to sync
            log.warn("askStream: no enqueue registered for {}, falling back to sync", skillId);
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

        if (agentFactory == null) {
            log.warn("AgentFactory not available for ai.askStream [{}]", skillId);
            return null;
        }

        Map<String, String> routing = storeService.getModelRouting("ai/ask");
        AiAskAgent agent = agentFactory.createAskAgent(routing);

        try {
            agent.askStream(prompt)
                .onPartialResponse(text -> {
                    if (text != null && !text.isEmpty()) {
                        enqueue.accept(() -> {
                            try { onChunk.execute(text); }
                            catch (Exception ex) { log.warn("onChunk error: {}", ex.getMessage()); }
                        });
                    }
                })
                .onCompleteResponse(response -> {
                    if (onDone != null && onDone.canExecute()) {
                        enqueue.accept(() -> {
                            try { onDone.execute(); }
                            catch (Exception ex) { log.warn("onDone error: {}", ex.getMessage()); }
                        });
                    }
                })
                .onError(error -> {
                    log.error("LLM ai/askStream failed for {}: {}", skillId, error.getMessage());
                    enqueue.accept(() -> {
                        try { onChunk.execute("ERROR: " + error.getMessage()); }
                        catch (Exception ex) { log.warn("onChunk error: {}", ex.getMessage()); }
                    });
                    if (onDone != null && onDone.canExecute()) {
                        enqueue.accept(() -> {
                            try { onDone.execute(); }
                            catch (Exception ex) { log.warn("onDone error: {}", ex.getMessage()); }
                        });
                    }
                })
                .start();
        } catch (Exception e) {
            log.error("LLM ai/askStream failed for {}: {}", skillId, e.getMessage());
            enqueue.accept(() -> {
                try { onChunk.execute("ERROR: " + e.getMessage()); }
                catch (Exception ex) { log.warn("onChunk error: {}", ex.getMessage()); }
            });
            if (onDone != null && onDone.canExecute()) {
                enqueue.accept(() -> {
                    try { onDone.execute(); }
                    catch (Exception ex) { log.warn("onDone error: {}", ex.getMessage()); }
                });
            }
        }

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
