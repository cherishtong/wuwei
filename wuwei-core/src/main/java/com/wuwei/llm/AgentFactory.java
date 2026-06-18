package com.wuwei.llm;

import com.wuwei.store.StoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Spring AI — native ChatClient factory for dynamic model routing.
 *
 * Design:
 * - {@link #chatClientFor(String)} reads DB routing config and builds a ChatClient.
 * - {@link #chatClientFor(String, Map)} accepts optional model overrides from the frontend.
 * - {@link #call(String, String, String)} and {@link #stream(String, String, String)}
 *   are convenience methods that wrap ChatClient.prompt().user().call().content().
 * - No more {@code @AiService} proxies, {@code TokenStream} callbacks, or separate
 *   Agent classes — Spring AI's ChatClient IS the universal API.
 */
@Service
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com/v1}")
    private String defaultBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String defaultApiKey;

    @Value("${spring.ai.openai.chat.options.model:deepseek-v4-pro}")
    private String defaultModel;

    private final StoreService storeService;

    public AgentFactory(StoreService storeService) {
        this.storeService = storeService;
    }

    // ── Primary API: build a ChatClient for a task type ─────────────

    /**
     * Build a ChatClient from DB routing config for the given task type.
     * Falls back to {@code spring.ai.openai.*} application properties.
     */
    public ChatClient chatClientFor(String taskType) {
        return chatClientFor(taskType, null);
    }

    /**
     * Build a ChatClient with optional runtime overrides (model, apiKey, apiUrl).
     * Overrides take priority over DB routing, which takes priority over defaults.
     */
    public ChatClient chatClientFor(String taskType, Map<String, String> modelOverride) {
        var routing = storeService.getModelRouting(taskType);

        String key = resolve(modelOverride, routing, "apiKey", defaultApiKey);
        String url = resolve(modelOverride, routing, "apiUrl", defaultBaseUrl);
        String model = resolve(modelOverride, routing, "model", defaultModel);

        // Build with the resolved config — ChatClient is lightweight, no need to cache
        var api = OpenAiApi.builder().baseUrl(url).apiKey(key).build();
        var chatModel = OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().model(model).build())
            .build();
        return ChatClient.builder(chatModel).build();
    }

    // ── Convenience methods ─────────────────────────────────────────

    /**
     * Synchronous chat with separate system + user prompts.
     *
     * @param systemPrompt optional system prompt (can be null)
     * @param userPrompt   the user message
     * @param taskType     routing key (e.g. "skill/generate", "ai/ask")
     * @return LLM text response, or null on error
     */
    public String call(String systemPrompt, String userPrompt, String taskType) {
        try {
            var spec = chatClientFor(taskType).prompt();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                spec = spec.system(systemPrompt);
            }
            return spec.user(userPrompt).call().content();
        } catch (Exception e) {
            log.warn("call({}) failed: {}", taskType, e.getMessage());
            return null;
        }
    }

    /**
     * Synchronous chat with model override.
     */
    public String call(String systemPrompt, String userPrompt, String taskType,
                       Map<String, String> modelOverride) {
        try {
            var spec = chatClientFor(taskType, modelOverride).prompt();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                spec = spec.system(systemPrompt);
            }
            return spec.user(userPrompt).call().content();
        } catch (Exception e) {
            log.warn("call({}) with override failed: {}", taskType, e.getMessage());
            return null;
        }
    }

    /**
     * Streaming chat via Spring AI Flux.
     * The caller subscribes to the Flux to receive token-by-token output.
     *
     * @param systemPrompt optional system prompt (can be null)
     * @param userPrompt   the user message
     * @param taskType     routing key
     * @return Flux<String> of token chunks
     */
    public Flux<String> stream(String systemPrompt, String userPrompt, String taskType) {
        return stream(systemPrompt, userPrompt, taskType, null);
    }

    /**
     * Streaming chat with model override.
     */
    public Flux<String> stream(String systemPrompt, String userPrompt, String taskType,
                               Map<String, String> modelOverride) {
        try {
            var spec = chatClientFor(taskType, modelOverride).prompt();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                spec = spec.system(systemPrompt);
            }
            return spec.user(userPrompt).stream().content();
        } catch (Exception e) {
            log.warn("stream({}) failed: {}", taskType, e.getMessage());
            return Flux.error(e);
        }
    }

    // ── Backward-compatible shortcuts ────────────────────────────────

    /** Raw single-prompt chat (used by AiCapability, SkillIndexer.search). */
    public String rawChat(String prompt) {
        return call(null, prompt, "ai/ask");
    }

    /** Single-prompt chat (used by SkillIndexer.indexSkill, ResumeDataService.generateMapping). */
    public String chat(String prompt) {
        return rawChat(prompt);
    }

    /** Chat with system + user (used by ResumeDataService.optimize — replaced by stream). */
    public String chat(String system, String user) {
        return call(system, user, "ai/ask");
    }

    // ── Status ───────────────────────────────────────────────────────

    public boolean enabled() {
        return defaultApiKey != null && !defaultApiKey.isBlank();
    }

    // ── Internal helpers ─────────────────────────────────────────────

    /** Resolve config value: override > DB routing > default. */
    private static String resolve(Map<String, String> override, Map<String, String> routing,
                                   String key, String defaultValue) {
        if (override != null) {
            String v = override.get(key);
            if (v != null && !v.isBlank()) return v;
        }
        String v = routing.get(key);
        if (v != null && !v.isBlank()) return v;
        return defaultValue;
    }
}
