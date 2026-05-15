package com.wuwei.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI-compatible API client for LLM Skill generation.
 * Uses java.net.http.HttpClient (JDK 21 built-in, no extra deps).
 */
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final Pattern THREE_FILE_PATTERN = Pattern.compile(
        "===\\s*(?:genome/)?([\\w.]+)\\s*===\\s*\\n(.*?)(?=\\n===|\\z)",
        Pattern.DOTALL);

    private final LlmConfig config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public LlmClient(LlmConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public LlmConfig config() {
        return config;
    }

    /**
     * Generate a Skill from a user intent description.
     * Returns parsed three-file bundle.
     */
    public SkillFiles generate(String systemPrompt, String intent,
                                String existingSkillsSummary) throws Exception {
        String userMessage = buildUserMessage(intent, existingSkillsSummary);
        String raw = chat(systemPrompt, userMessage);
        return parseThreeFiles(raw);
    }

    /**
     * Repair a failing Skill based on error feedback.
     */
    public SkillFiles repair(String repairPrompt, SkillFiles current,
                             String error, int attempt) throws Exception {
        String userMessage = buildRepairMessage(error, current, attempt);
        String raw = chat(repairPrompt, userMessage);
        return parseThreeFiles(raw);
    }

    /**
     * Refine an existing Skill based on user feedback.
     */
    public SkillFiles refine(String refinePrompt, String skillJson, String uiJson,
                            String handlersJs, String feedback) throws Exception {
        String userMessage = buildRefineMessage(skillJson, uiJson, handlersJs, feedback);
        String raw = chat(refinePrompt, userMessage);
        return parseThreeFiles(raw);
    }

    /**
     * Public entry point for AiCapability — simple system+user → text.
     */
    public String chatSimple(String systemPrompt, String userMessage) throws Exception {
        return chat(systemPrompt, userMessage);
    }

    /**
     * Streaming chat for AiCapability.askStream().
     * Uses SSE (server-sent events) parsing on a blocking InputStream —
     * the caller MUST invoke this from a background thread.
     *
     * @param onChunk called for each content delta (may be called many times)
     * @param onDone  called when the stream finishes normally
     * @param onError called on transport/protocol errors
     */
    public void chatStream(String systemPrompt, String userMessage,
                           StreamChunk onChunk, StreamDone onDone,
                           StreamError onError) {
        String apiKey = config.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            onError.accept("API key not configured");
            return;
        }
        String baseUrl = config.effectiveBaseUrl();
        URI uri = URI.create(baseUrl + "/chat/completions");

        Map<String, Object> body = Map.of(
            "model", config.model(),
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
            ),
            "temperature", 0.3,
            "max_tokens", 4096,
            "stream", true
        );

        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(Math.max(config.timeoutSeconds(), 120)))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errBody = new String(response.body().readAllBytes(),
                    StandardCharsets.UTF_8);
                onError.accept("LLM API error " + response.statusCode() + ": "
                    + (errBody.length() > 200 ? errBody.substring(0, 200) : errBody));
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(),
                        StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) break;
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        try {
                            JsonNode node = mapper.readTree(data);
                            JsonNode choices = node.get("choices");
                            if (choices != null && choices.size() > 0) {
                                JsonNode delta = choices.get(0).get("delta");
                                if (delta != null) {
                                    JsonNode content = delta.get("content");
                                    if (content != null && !content.isNull()
                                        && !content.asText().isEmpty()) {
                                        onChunk.accept(content.asText());
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                            // Skip unparseable SSE lines
                        }
                    }
                }
            }
            onDone.run();
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                onError.accept("Stream cancelled");
            } else {
                log.error("chatStream failed: {}", e.getMessage());
                onError.accept("Stream error: " + e.getMessage());
            }
        }
    }

    @FunctionalInterface
    public interface StreamChunk { void accept(String text); }

    @FunctionalInterface
    public interface StreamDone { void run(); }

    @FunctionalInterface
    public interface StreamError { void accept(String message); }

    private String chat(String systemPrompt, String userMessage) throws Exception {
        String apiKey = config.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "API key not found. Set environment variable " + config.apiKeyEnv()
                + " or pass -D" + config.apiKeyEnv() + "=<key>");
        }

        String baseUrl = config.effectiveBaseUrl();
        URI uri = URI.create(baseUrl + "/chat/completions");

        Map<String, Object> body = Map.of(
            "model", config.model(),
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
            ),
            "temperature", 0.3,
            "max_tokens", 4096
        );

        String json = mapper.writeValueAsString(body);

        int maxRetries = config.maxRetries();
        Exception lastError = null;
        for (int retry = 0; retry <= maxRetries; retry++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

                HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    String errBody = response.body();
                    log.warn("LLM API returned {}: {}", response.statusCode(),
                        errBody.length() > 300 ? errBody.substring(0, 300) : errBody);
                    throw new RuntimeException("LLM API error " + response.statusCode()
                        + ": " + (errBody.length() > 200 ? errBody.substring(0, 200) : errBody));
                }

                JsonNode resp = mapper.readTree(response.body());
                return resp.get("choices").get(0).get("message").get("content").asText();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                lastError = e;
                if (retry < maxRetries) {
                    long delay = (long) Math.pow(2, retry) * 500; // 0.5s, 1s, 2s
                    log.info("LLM retry {}/{} after {}ms", retry + 1, maxRetries, delay);
                    Thread.sleep(delay);
                }
            }
        }
        throw lastError;
    }

    private String buildUserMessage(String intent, String existingSkillsSummary) {
        return """
            ## 用户需求
            %s

            ## 已安装的 Skill（ID 不得重复）
            %s
            """.formatted(intent, existingSkillsSummary);
    }

    private String buildRefineMessage(String skillJson, String uiJson,
                                       String handlersJs, String feedback) {
        return """
            ## 用户反馈
            %s

            ## 当前 skill.json
            ```json
            %s
            ```

            ## 当前 ui.json
            ```json
            %s
            ```

            ## 当前 handlers.js
            ```js
            %s
            ```
            """.formatted(feedback, skillJson, uiJson, handlersJs);
    }

    private String buildRepairMessage(String error, SkillFiles current, int attempt) {
        return """
            ## 错误信息（第 %d 次修复）
            %s

            ## 当前 skill.json
            ```json
            %s
            ```

            ## 当前 genome/ui.json
            ```json
            %s
            ```

            ## 当前 genome/handlers.js
            ```js
            %s
            ```
            """.formatted(attempt, error, current.skillJson(), current.uiJson(), current.handlersJs());
    }

    SkillFiles parseThreeFiles(String raw) {
        Matcher m = THREE_FILE_PATTERN.matcher(raw);
        String skillJson = "{}", uiJson = "{}", handlersJs = "";
        while (m.find()) {
            String filename = m.group(1).trim();
            String content = m.group(2).trim();
            // Remove leading ``` fences if present
            content = content.replaceAll("^```(?:json|js)?\\s*\\n?", "")
                             .replaceAll("\\n```\\s*$", "");
            switch (filename) {
                case "skill.json" -> skillJson = content;
                case "ui.json" -> uiJson = content;
                case "handlers.js" -> handlersJs = content;
            }
        }
        // Fallback: if no separators found, try JSON block extraction
        if (skillJson.equals("{}") && uiJson.equals("{}")) {
            return parseJsonBlocks(raw);
        }
        return new SkillFiles(skillJson, uiJson, handlersJs);
    }

    private SkillFiles parseJsonBlocks(String raw) {
        String skillJson = extractJsonBlock(raw, 0);
        String uiJson = extractJsonBlock(raw, 1);
        String handlersJs = extractJsBlock(raw);
        return new SkillFiles(skillJson, uiJson, handlersJs);
    }

    private String extractJsonBlock(String text, int skip) {
        int depth = 0, start = -1, skipped = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    if (skipped == skip) { start = i; }
                    skipped++;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    return text.substring(start, i + 1).trim();
                }
            }
        }
        return "{}";
    }

    private String extractJsBlock(String text) {
        // Try ```js ... ``` fence first
        Pattern jsFence = Pattern.compile("```(?:js|javascript)\\s*\\n(.*?)\\n```", Pattern.DOTALL);
        Matcher m = jsFence.matcher(text);
        if (m.find()) return m.group(1).trim();

        // Try after the last JSON block
        int lastJsonEnd = -1;
        int depth = 0, start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0) lastJsonEnd = i + 1;
            }
        }
        if (lastJsonEnd > 0 && lastJsonEnd < text.length() - 10) {
            String after = text.substring(lastJsonEnd).trim();
            return after.replaceAll("^```(?:js|javascript)?\\s*\\n?", "")
                        .replaceAll("\\n```\\s*$", "");
        }
        return "";
    }
}
