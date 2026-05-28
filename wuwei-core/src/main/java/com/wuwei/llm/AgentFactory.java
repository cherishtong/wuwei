package com.wuwei.llm;

import com.wuwei.store.StoreService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * Creates LangChain4j AiServices agents with per-request model routing + ChatMemory.
 *
 * Each {@code create*} method merges user override → SQLite routing → defaults,
 * builds an OpenAI-compatible model, and wraps it in an AiServices proxy with
 * persistent ChatMemory via {@link SummarizingChatMemoryStore}.
 */
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final StoreService storeService;
    private final SummarizingChatMemoryStore memoryStore;

    public AgentFactory(StoreService storeService, SummarizingChatMemoryStore memoryStore) {
        this.storeService = storeService;
        this.memoryStore = memoryStore;
    }

    // ── Static utility (for bootstrapping, no ChatMemory) ──────────────

    /** Create a bare AiAskAgent from routing config — used for memory summarization. */
    public static AiAskAgent createAskAgentStatic(Map<String, String> routing) {
        LlmConfig config = LlmConfig.fromMap(routing);
        ChatModel model = OpenAiChatModel.builder()
            .baseUrl(config.apiUrl() != null && !config.apiUrl().isBlank() ? config.apiUrl() : "https://api.deepseek.com/v1")
            .apiKey(config.apiKey() != null && !config.apiKey().isBlank() ? config.apiKey()
                : (System.getenv("OPENAI_API_KEY") != null ? System.getenv("OPENAI_API_KEY") : ""))
            .modelName(config.model())
            .timeout(Duration.ofSeconds(120))
            .maxRetries(1)
            .logRequests(false)
            .logResponses(false)
            .build();
        StreamingChatModel streamingModel = OpenAiStreamingChatModel.builder()
            .baseUrl(config.apiUrl() != null && !config.apiUrl().isBlank() ? config.apiUrl() : "https://api.deepseek.com/v1")
            .apiKey(config.apiKey() != null && !config.apiKey().isBlank() ? config.apiKey()
                : (System.getenv("OPENAI_API_KEY") != null ? System.getenv("OPENAI_API_KEY") : ""))
            .modelName(config.model())
            .timeout(Duration.ofSeconds(120))
            .logRequests(false)
            .logResponses(false)
            .build();
        return AiServices.builder(AiAskAgent.class)
            .chatModel(model)
            .streamingChatModel(streamingModel)
            .build();
    }

    // ── Public factory methods ─────────────────────────────────────────

    public SkillGenerateAgent createGenerateAgent(String skillId, Map<String, String> override) {
        LlmConfig config = resolveConfig("skill/generate", override);
        StreamingChatModel model = buildStreamingModel(config);
        return AiServices.builder(SkillGenerateAgent.class)
            .streamingChatModel(model)
            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .chatMemoryStore(memoryStore)
                .build())
            .build();
    }

    public SkillRepairAgent createRepairAgent(String skillId, Map<String, String> override) {
        LlmConfig config = resolveConfig("skill/repair", override);
        StreamingChatModel model = buildStreamingModel(config);
        return AiServices.builder(SkillRepairAgent.class)
            .streamingChatModel(model)
            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .chatMemoryStore(memoryStore)
                .build())
            .build();
    }

    public DriftAnalysisAgent createDriftAgent(String skillId, Map<String, String> override) {
        LlmConfig config = resolveConfig("skill/drift", override);
        ChatModel model = buildChatModel(config);
        return AiServices.builder(DriftAnalysisAgent.class)
            .chatModel(model)
            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .chatMemoryStore(memoryStore)
                .build())
            .build();
    }

    public AiAskAgent createAskAgent(Map<String, String> override) {
        LlmConfig config = resolveConfig("ai/ask", override);
        ChatModel model = buildChatModel(config);
        StreamingChatModel streamingModel = buildStreamingModel(config);
        return AiServices.builder(AiAskAgent.class)
            .chatModel(model)
            .streamingChatModel(streamingModel)
            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .chatMemoryStore(memoryStore)
                .build())
            .build();
    }

    // ── Internal ───────────────────────────────────────────────────────

    private LlmConfig resolveConfig(String taskType, Map<String, String> override) {
        Map<String, String> routing = storeService.getModelRouting(taskType);
        return LlmConfig.merge(override, routing);
    }

    private ChatModel buildChatModel(LlmConfig config) {
        return OpenAiChatModel.builder()
            .baseUrl(resolveBaseUrl(config))
            .apiKey(resolveApiKey(config))
            .modelName(config.model())
            .timeout(Duration.ofSeconds(300))
            .maxRetries(2)
            .logRequests(false)
            .logResponses(false)
            .build();
    }

    private StreamingChatModel buildStreamingModel(LlmConfig config) {
        System.out.println("[kernel] [AgentFactory] buildStreamingModel: baseUrl=" + resolveBaseUrl(config) + " model=" + config.model() + " hasApiKey=" + (!resolveApiKey(config).isEmpty()));
        return OpenAiStreamingChatModel.builder()
            .baseUrl(resolveBaseUrl(config))
            .apiKey(resolveApiKey(config))
            .modelName(config.model())
            .timeout(Duration.ofSeconds(300))
            .logRequests(true)
            .logResponses(true)
            .build();
    }

    private String resolveBaseUrl(LlmConfig config) {
        if (config.apiUrl() != null && !config.apiUrl().isBlank()) return config.apiUrl();
        return "https://api.deepseek.com/v1";
    }

    private String resolveApiKey(LlmConfig config) {
        if (config.apiKey() != null && !config.apiKey().isBlank()) return config.apiKey();
        String env = System.getenv("OPENAI_API_KEY");
        return env != null ? env : "";
    }
}
