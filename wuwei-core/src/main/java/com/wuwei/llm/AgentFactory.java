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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * Creates LangChain4j AiServices agents with per-request model routing + ChatMemory.
 *
 * Defaults come from Spring Boot configuration (application.yml / env vars).
 * Model routing overrides come from the SQLite model_routing table (set via frontend).
 *
 * Priority: user override → model_routing → application.yml defaults
 */
@Service
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final StoreService storeService;
    private final SummarizingChatMemoryStore memoryStore;

    @Value("${langchain4j.open-ai.chat-model.base-url:https://api.deepseek.com/v1}")
    private String defaultBaseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key:}")
    private String defaultApiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name:deepseek-v4-pro}")
    private String defaultModel;

    @Value("${langchain4j.open-ai.chat-model.timeout:300s}")
    private Duration defaultTimeout;

    public AgentFactory(StoreService storeService, SummarizingChatMemoryStore memoryStore) {
        this.storeService = storeService;
        this.memoryStore = memoryStore;
    }

    // ── Static utility (for bootstrapping, no ChatMemory) ──────────────

    /** Create a bare AiAskAgent from routing config — used for memory summarization. */
    public static AiAskAgent createAskAgentStatic(Map<String, String> routing) {
        LlmConfig config = LlmConfig.fromMap(routing);
        ChatModel model = OpenAiChatModel.builder()
            .baseUrl(apiUrlOrDefault(config))
            .apiKey(apiKeyOrDefault(config))
            .modelName(config.model())
            .timeout(Duration.ofSeconds(300))
            .maxRetries(1)
            .logRequests(false).logResponses(false)
            .build();
        StreamingChatModel streamingModel = OpenAiStreamingChatModel.builder()
            .baseUrl(apiUrlOrDefault(config))
            .apiKey(apiKeyOrDefault(config))
            .modelName(config.model())
            .timeout(Duration.ofSeconds(300))
            .logRequests(false).logResponses(false)
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
                .id(memoryId).maxMessages(20).chatMemoryStore(memoryStore).build())
            .build();
    }

    public SkillGenerateSyncAgent createGenerateSyncAgent(String skillId, Map<String, String> override) {
        LlmConfig config = resolveConfig("skill/generate", override);
        ChatModel model = buildChatModel(config);
        return AiServices.builder(SkillGenerateSyncAgent.class)
            .chatModel(model)
            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId).maxMessages(20).chatMemoryStore(memoryStore).build())
            .build();
    }

    public SkillRepairAgent createRepairAgent(String skillId, Map<String, String> override) {
        LlmConfig config = resolveConfig("skill/repair", override);
        ChatModel model = buildChatModel(config);
        return AiServices.builder(SkillRepairAgent.class)
            .chatModel(model)
            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId).maxMessages(20).chatMemoryStore(memoryStore).build())
            .build();
    }

    public DriftAnalysisAgent createDriftAgent(String skillId, Map<String, String> override) {
        LlmConfig config = resolveConfig("skill/drift", override);
        ChatModel model = buildChatModel(config);
        return AiServices.builder(DriftAnalysisAgent.class)
            .chatModel(model)
            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId).maxMessages(20).chatMemoryStore(memoryStore).build())
            .build();
    }

    public PlannerAgent createPlannerAgent(Map<String, String> override) {
        LlmConfig config = resolveConfig("skill/generate", override);
        ChatModel model = buildChatModel(config);
        return AiServices.builder(PlannerAgent.class).chatModel(model).build();
    }

    public ChatModel createChatModel(Map<String, String> override) {
        return buildChatModel(resolveConfig("skill/generate", override));
    }

    public AiAskAgent createAskAgent(Map<String, String> override) {
        LlmConfig config = resolveConfig("ai/ask", override);
        ChatModel model = buildChatModel(config);
        StreamingChatModel streamingModel = buildStreamingModel(config);
        return AiServices.builder(AiAskAgent.class)
            .chatModel(model).streamingChatModel(streamingModel)
            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId).maxMessages(20).chatMemoryStore(memoryStore).build())
            .build();
    }

    public String chat(String prompt) { return rawChat(prompt); }

    public String rawChat(String prompt) {
        try {
            LlmConfig config = resolveConfig("ai/ask", null);
            ChatModel model = buildChatModel(config);
            var response = model.chat(
                dev.langchain4j.data.message.SystemMessage.from("You are a code analysis assistant. Output ONLY valid JSON, no markdown fences, no explanations."),
                dev.langchain4j.data.message.UserMessage.from(prompt));
            return response.aiMessage().text();
        } catch (Exception e) {
            log.warn("rawChat failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Internal ───────────────────────────────────────────────────────

    LlmConfig resolveConfig(String taskType, Map<String, String> override) {
        Map<String, String> routing = storeService.getModelRouting(taskType);
        return LlmConfig.merge(override, routing);
    }

    ChatModel buildChatModel(LlmConfig config) {
        String url = config.apiUrl() != null && !config.apiUrl().isBlank() ? config.apiUrl() : defaultBaseUrl;
        String key = config.apiKey() != null && !config.apiKey().isBlank() ? config.apiKey() : defaultApiKey;
        log.debug("buildChatModel: baseUrl={} model={}", url, config.model());
        return OpenAiChatModel.builder()
            .baseUrl(url).apiKey(key).modelName(config.model())
            .timeout(defaultTimeout).maxRetries(1)
            .logRequests(false).logResponses(true)
            .build();
    }

    StreamingChatModel buildStreamingModel(LlmConfig config) {
        String url = config.apiUrl() != null && !config.apiUrl().isBlank() ? config.apiUrl() : defaultBaseUrl;
        String key = config.apiKey() != null && !config.apiKey().isBlank() ? config.apiKey() : defaultApiKey;
        return OpenAiStreamingChatModel.builder()
            .baseUrl(url).apiKey(key).modelName(config.model())
            .timeout(defaultTimeout)
            .logRequests(false).logResponses(true)
            .build();
    }

    private static String apiUrlOrDefault(LlmConfig c) {
        return c.apiUrl() != null && !c.apiUrl().isBlank() ? c.apiUrl() : "https://api.deepseek.com/v1";
    }

    private static String apiKeyOrDefault(LlmConfig c) {
        return c.apiKey() != null && !c.apiKey().isBlank() ? c.apiKey()
            : (System.getenv("OPENAI_API_KEY") != null ? System.getenv("OPENAI_API_KEY") : "");
    }
}
