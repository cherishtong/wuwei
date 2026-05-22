package com.wuwei.llm;

import java.util.Map;

/**
 * Model routing configuration for a specific task type.
 * Built from StoreService.getModelRouting() which reads model_routing table.
 */
public record LlmConfig(
    String provider,
    String model,
    String apiKey,
    String apiUrl,
    String params
) {
    public static LlmConfig fromMap(Map<String, String> map) {
        return new LlmConfig(
            map.getOrDefault("provider", "openai"),
            map.getOrDefault("model", "gpt-4o-mini"),
            map.getOrDefault("apiKey", ""),
            map.getOrDefault("apiUrl", ""),
            map.getOrDefault("params", "{}")
        );
    }

    /** Merge user override (can be empty) over routing defaults. */
    public static LlmConfig merge(Map<String, String> override, Map<String, String> routing) {
        if (override == null) override = Map.of();
        if (routing == null) routing = Map.of();
        return new LlmConfig(
            !override.getOrDefault("provider", "").isBlank() ? override.get("provider") : routing.getOrDefault("provider", "openai"),
            !override.getOrDefault("model", "").isBlank() ? override.get("model") : routing.getOrDefault("model", "gpt-4o-mini"),
            !override.getOrDefault("apiKey", "").isBlank() ? override.get("apiKey") : routing.getOrDefault("apiKey", ""),
            !override.getOrDefault("apiUrl", "").isBlank() ? override.get("apiUrl") : routing.getOrDefault("apiUrl", ""),
            !override.getOrDefault("params", "").isBlank() ? override.get("params") : routing.getOrDefault("params", "{}")
        );
    }
}
