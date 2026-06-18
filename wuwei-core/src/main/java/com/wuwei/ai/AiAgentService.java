package com.wuwei.ai;

import com.wuwei.llm.AgentFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Spring AI — streaming chat service for SSE endpoints.
 * Supports multi-turn conversation history per memoryId.
 */
@Service
public class AiAgentService {

    private static final Logger log = LoggerFactory.getLogger(AiAgentService.class);

    private final AgentFactory agentFactory;
    private final ConcurrentHashMap<String, List<SimpleMessage>> chatHistories = new ConcurrentHashMap<>();

    public AiAgentService(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    /** Streaming chat via Spring AI Flux with conversation history. */
    public void streamChat(String memoryId, String systemPrompt, String userMessage,
                           Consumer<String> onToken,
                           Consumer<String> onComplete,
                           Consumer<Throwable> onError) {

        // Build prompt with history
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append(systemPrompt).append("\n\n");
        }

        // Include prior conversation turns
        var history = chatHistories.getOrDefault(memoryId, List.of());
        if (!history.isEmpty()) {
            prompt.append("## 对话历史\n");
            for (var entry : history) {
                prompt.append(entry.role()).append(": ").append(entry.content()).append("\n");
            }
            prompt.append("\n");
        }

        // Current user message
        prompt.append("user: ").append(userMessage).append("\n\nassistant: ");

        log.info("streamChat[{}]: prompt len={} historyTurns={}",
            memoryId, prompt.length(), history.size() / 2);

        ChatClient client = agentFactory.chatClientFor("ai/ask");
        Flux<String> flux = client.prompt().user(prompt.toString()).stream().content();

        StringBuilder buffer = new StringBuilder();
        flux.doOnNext(token -> { buffer.append(token); onToken.accept(token); })
            .doOnComplete(() -> {
                log.info("streamChat[{}]: done, {} chars", memoryId, buffer.length());
                // Save to history
                addToHistory(memoryId, "user", userMessage);
                addToHistory(memoryId, "assistant", buffer.toString());
                onComplete.accept(buffer.toString());
            })
            .doOnError(e -> {
                log.error("streamChat[{}]: error - {}", memoryId, e.getMessage());
                onError.accept(e);
            })
            .subscribe();
    }

    /** Clear conversation history for a session. */
    public void clearSession(String memoryId) {
        chatHistories.remove(memoryId);
        log.debug("Cleared session: {}", memoryId);
    }

    /** Add a message to the conversation history. */
    public void addToHistory(String memoryId, String role, String content) {
        chatHistories.computeIfAbsent(memoryId, k -> new ArrayList<>())
            .add(new SimpleMessage(role, content));
    }

    public String buildPrompt(String agentType, Map<String, Object> context) {
        return switch (agentType) {
            case "resume-optimize" -> {
                var sb = new StringBuilder();
                sb.append("你是专业简历优化顾问。优化用户简历数据使其更专业有竞争力。\n");
                sb.append("原则: STAR法则量化、措辞专业化、保持JSON结构、听取每次反馈。\n\n");
                if (context.containsKey("data"))
                    sb.append("## 当前简历数据\n```json\n").append(context.get("data")).append("\n```\n\n");
                if (context.containsKey("mapping"))
                    sb.append("## 模板映射\n```json\n").append(context.get("mapping")).append("\n```\n");
                sb.append("每次返回优化后的完整JSON。");
                yield sb.toString();
            }
            case "general" -> "你是Wuwei的AI助手，用中文回答。";
            default -> "你是Wuwei的AI助手，用中文回答。";
        };
    }

    private record SimpleMessage(String role, String content) {}
}
