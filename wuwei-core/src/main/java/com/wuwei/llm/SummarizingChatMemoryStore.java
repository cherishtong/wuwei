package com.wuwei.llm;

import com.wuwei.store.StoreService;
import org.springframework.stereotype.Component;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persistent {@link ChatMemoryStore} backed by SQLite with auto-summarization.
 *
 * When {@link dev.langchain4j.memory.chat.TokenWindowChatMemory} evicts old messages,
 * those messages are summarized and stored so the LLM always retains context about
 * the skill's full evolution history — not just the most recent N turns.
 */
@Component
public class SummarizingChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SummarizingChatMemoryStore.class);

    private final StoreService storeService;
    private final AiAskAgent summarizer;

    public SummarizingChatMemoryStore(StoreService storeService, AiAskAgent summarizer) {
        this.storeService = storeService;
        this.summarizer = summarizer;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String skillId = memoryId.toString();
        List<Map<String, Object>> rows = storeService.loadChatMessages(skillId);

        String summary = storeService.loadMemorySummary(skillId);
        List<ChatMessage> messages = new ArrayList<>();

        // Prepend summary as system message if one exists from a previous overflow
        if (summary != null && !summary.isBlank()) {
            messages.add(SystemMessage.from("## 历史摘要（早期对话已被压缩）\n\n" + summary));
        }

        for (Map<String, Object> row : rows) {
            String type = (String) row.get("type");
            String text = (String) row.get("text");
            if (text == null) continue;
            messages.add(switch (type) {
                case "SYSTEM" -> SystemMessage.from(text);
                case "USER" -> UserMessage.from(text);
                case "AI" -> AiMessage.from(text);
                default -> UserMessage.from(text);
            });
        }

        return messages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String skillId = memoryId.toString();

        // Check for eviction: compare what's stored vs what TokenWindowChatMemory kept
        List<Map<String, Object>> stored = storeService.loadChatMessages(skillId);
        int storedCount = stored.size();
        int keptCount = messages.size();

        // If messages were evicted, summarize them
        if (storedCount > 0 && keptCount < storedCount) {
            int evicted = storedCount - keptCount;
            try {
                String oldSummary = storeService.loadMemorySummary(skillId);
                String evictedText = buildEvictedText(stored, evicted);
                String summarizePrompt = buildSummarizePrompt(evictedText, oldSummary);
                String newSummary = summarizer.ask(summarizePrompt);
                if (newSummary != null && !newSummary.isBlank()) {
                    String range = "msg 1-" + evicted;
                    storeService.saveMemorySummary(skillId, newSummary, range);
                    log.debug("Summarized {} evicted messages for skill {}", evicted, skillId);
                }
            } catch (Exception e) {
                log.warn("Failed to summarize evicted messages for {}: {}", skillId, e.getMessage());
            }
        }

        // Save current window messages to SQLite
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChatMessage msg : messages) {
            String type;
            String text;
            if (msg instanceof SystemMessage sm) {
                // Skip the summary system message we injected — don't persist it
                if (sm.text().startsWith("## 历史摘要")) continue;
                type = "SYSTEM";
                text = sm.text();
            } else if (msg instanceof UserMessage um) {
                type = "USER";
                text = um.singleText();
            } else if (msg instanceof AiMessage am) {
                type = "AI";
                text = am.text();
            } else {
                continue;
            }
            rows.add(Map.of("type", type, "text", text != null ? text : ""));
        }
        storeService.saveChatMessages(skillId, rows);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        storeService.deleteChatMemory(memoryId.toString());
    }

    private String buildEvictedText(List<Map<String, Object>> stored, int evictedCount) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(evictedCount, stored.size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = stored.get(i);
            sb.append("[").append(row.get("type")).append("] ")
              .append(row.get("text")).append("\n\n");
        }
        return sb.toString();
    }

    private String buildSummarizePrompt(String evictedText, String existingSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("将以下对话历史压缩为不超过 300 字的摘要。保留关键决策、用户需求和设计意图。\n\n");
        if (existingSummary != null && !existingSummary.isBlank()) {
            sb.append("现有摘要（请合并）:\n").append(existingSummary).append("\n\n");
        }
        sb.append("新增对话:\n").append(evictedText);
        return sb.toString();
    }
}
