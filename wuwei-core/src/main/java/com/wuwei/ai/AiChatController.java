package com.wuwei.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE endpoint for AI streaming chat.
 *
 * Usage (from browser):
 *   const sse = new EventSource('/api/ai/chat/memory123?message=优化简历');
 *   sse.onmessage = (e) => { renderToken(e.data); };
 *   sse.addEventListener('done', () => { sse.close(); });
 *   sse.addEventListener('error', (e) => { handleError(e.data); });
 *
 * Events:
 *   onmessage  — token chunks (SSE default event)
 *   done       — generation complete (carries usage stats)
 *   error      — error occurred
 */
@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);

    private final AiAgentService agentService;
    private final ConcurrentHashMap<String, SseEmitter> activeEmitters = new ConcurrentHashMap<>();

    public AiChatController(AiAgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * Start or continue a streaming chat session.
     *
     * @param memoryId    session ID (e.g. "resume-builder-xxx")
     * @param message     user's message
     * @param agentType   agent type for system prompt (optional, default "general")
     * @param context     JSON context for the agent (optional)
     */
    @GetMapping("/chat/{memoryId}")
    public SseEmitter chat(
            @PathVariable String memoryId,
            @RequestParam String message,
            @RequestParam(defaultValue = "general") String agentType,
            @RequestParam(required = false) String context) {

        SseEmitter emitter = new SseEmitter(0L); // no timeout
        activeEmitters.put(memoryId, emitter);

        Map<String, Object> ctx = context != null ? parseContext(context) : Map.of();
        String systemPrompt = agentService.buildPrompt(agentType, ctx);

        log.info("AI chat start: memoryId={} agentType={} msg={}", memoryId, agentType,
            message.substring(0, Math.min(50, message.length())));

        new Thread(() -> {
            try {
                agentService.streamChat(memoryId, systemPrompt, message,
                    // onToken
                    token -> sendSse(emitter, null, token),
                    // onComplete
                    result -> {
                        sendSse(emitter, "done", result);
                        emitter.complete();
                        activeEmitters.remove(memoryId);
                    },
                    // onError
                    error -> {
                        sendSse(emitter, "error", error.getMessage());
                        emitter.completeWithError(error);
                        activeEmitters.remove(memoryId);
                    }
                );
            } catch (Exception e) {
                log.error("AI chat error for {}", memoryId, e);
                emitter.completeWithError(e);
                activeEmitters.remove(memoryId);
            }
        }, "ai-chat-" + memoryId).start();

        return emitter;
    }

    /** Cancel an active chat session. */
    @DeleteMapping("/chat/{memoryId}")
    public Map<String, Object> cancel(@PathVariable String memoryId) {
        var emitter = activeEmitters.remove(memoryId);
        if (emitter != null) emitter.complete();
        agentService.clearSession(memoryId);
        return Map.of("cancelled", memoryId);
    }

    private void sendSse(SseEmitter emitter, String event, String data) {
        try {
            if (event != null) {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.TEXT_PLAIN));
            } else {
                emitter.send(data, MediaType.TEXT_PLAIN);
            }
        } catch (IOException e) {
            log.debug("SSE send failed (client disconnected): {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseContext(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
