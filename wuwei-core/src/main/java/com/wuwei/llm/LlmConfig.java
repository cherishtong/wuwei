package com.wuwei.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LlmConfig(
    @JsonProperty("provider") String provider,
    @JsonProperty("model") String model,
    @JsonProperty("apiKeyEnv") String apiKeyEnv,
    @JsonProperty("apiKey") String apiKey,
    @JsonProperty("baseUrl") String baseUrl,
    @JsonProperty("timeoutSeconds") int timeoutSeconds,
    @JsonProperty("maxRetries") int maxRetries
) {
    public static final LlmConfig DISABLED = new LlmConfig(
        "none", "", "", null, null, 60, 0
    );

    public boolean enabled() {
        return !"none".equals(provider);
    }

    public String apiKey() {
        // 1. Direct apiKey field in wuwei.json
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        // 2. Environment variable (e.g. OPENAI_API_KEY)
        if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
            String key = System.getenv(apiKeyEnv);
            if (key != null && !key.isBlank()) return key;
            // fallback to -D flag
            key = System.getProperty(apiKeyEnv);
            if (key != null && !key.isBlank()) return key;
        }
        return null;
    }

    public String effectiveBaseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) return baseUrl;
        return switch (provider) {
            case "openai" -> "https://api.openai.com/v1";
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "custom" -> throw new IllegalStateException("custom provider requires baseUrl");
            default -> "https://api.openai.com/v1";
        };
    }
}
