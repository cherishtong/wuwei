package com.wuwei.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.store.StoreService;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Web search capability for Skills.
 * Uses Tavily Search API directly via JDK HttpClient — zero extra dependencies.
 *
 * Configuration via model_routing table:
 *   task_type='websearch', provider='tavily', api_key='tvly-xxx'
 *
 * JS API:
 *   capability.websearch.search(query, {limit: 5}) → {results: [{title, url, snippet}], answer: "..."}
 */
public class WebSearchCapability {

    private static final Logger log = LoggerFactory.getLogger(WebSearchCapability.class);

    private static final String TAVILY_URL = "https://api.tavily.com/search";
    private static final int MAX_RESULTS = 10;
    private static final int MAX_QUOTA_PER_MIN = 50;

    private final HttpClient httpClient;
    private final StoreService storeService;
    private final ObjectMapper mapper;
    private final Map<String, QuotaTracker> quotas = new ConcurrentHashMap<>();

    public WebSearchCapability(StoreService storeService, ObjectMapper mapper) {
        this.storeService = storeService;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public ProxyObject forSkill(String skillId) {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                if ("search".equals(key)) {
                    return (ProxyExecutable) args -> {
                        String query = args[0].asString();
                        int limit = args.length > 1 ? args[1].asInt() : 5;
                        return executeSearch(skillId, query, limit);
                    };
                }
                return null;
            }

            @Override
            public boolean hasMember(String key) { return "search".equals(key); }

            @Override
            public Set<String> getMemberKeys() { return Set.of("search"); }

            @Override
            public void putMember(String key, Value value) {}

            @Override
            public boolean removeMember(String key) { return false; }
        };
    }

    private Object executeSearch(String skillId, String query, int limit) {
        log.debug("websearch.search [{}]: {}... (limit={})",
            skillId, query.length() > 60 ? query.substring(0, 60) : query, limit);

        // Quota check
        QuotaTracker tracker = quotas.computeIfAbsent(skillId,
            k -> new QuotaTracker(MAX_QUOTA_PER_MIN));
        if (!tracker.tryAcquire()) {
            throw new RuntimeException("WebSearch quota exceeded (" + MAX_QUOTA_PER_MIN + "/min)");
        }

        // Get API key from model_routing
        Map<String, String> routing = storeService.getModelRouting("websearch");
        String apiKey = routing.getOrDefault("apiKey", "");
        if (apiKey.isEmpty()) {
            throw new RuntimeException("WebSearch not configured — add websearch routing with api_key in system settings");
        }

        int cappedLimit = Math.min(limit, MAX_RESULTS);

        try {
            String requestBody = mapper.writeValueAsString(Map.of(
                "api_key", apiKey,
                "query", query,
                "search_depth", "basic",
                "max_results", cappedLimit,
                "include_answer", true
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TAVILY_URL))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Wuwei/0.0.1-beta")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(15))
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());

            // Build results array as list of maps
            List<Map<String, Object>> results = new ArrayList<>();
            if (json.has("results")) {
                for (JsonNode r : json.get("results")) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("title", r.has("title") ? r.get("title").asText() : "");
                    item.put("url", r.has("url") ? r.get("url").asText() : "");
                    item.put("snippet", r.has("content") ? r.get("content").asText() : "");
                    if (r.has("score")) item.put("score", r.get("score").asDouble());
                    results.add(item);
                }
            }

            String answer = json.has("answer") ? json.get("answer").asText() : "";

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("results", results);
            resultMap.put("answer", answer);

            log.info("websearch.search [{}]: {} results for '{}'",
                skillId, results.size(),
                query.length() > 40 ? query.substring(0, 40) + "..." : query);

            return CryptoCapability.mapProxy(resultMap);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) {
            log.error("websearch.search failed [{}]: {}", skillId, e.getMessage());
            throw new RuntimeException("Web search failed: " + e.getMessage(), e);
        }
    }

    // ── Proxy execution for browser-js ─────────────────────────────

    public Object executeProxy(String skillId, String method, List<Object> args) {
        if ("search".equals(method)) {
            String query = (String) args.get(0);
            int limit = args.size() > 1 ? ((Number) args.get(1)).intValue() : 5;
            Object result = executeSearch(skillId, query, limit);
            // Unwrap ProxyObject for JSON serialization
            if (result instanceof ProxyObject po) {
                Map<String, Object> plain = new LinkedHashMap<>();
                plain.put("results", po.getMember("results"));
                plain.put("answer", po.getMember("answer"));
                return plain;
            }
            return result;
        }
        return Map.of("error", "Unknown websearch method: " + method);
    }

    // ── Quota tracking ─────────────────────────────────────────────

    private static class QuotaTracker {
        private final int maxPerMinute;
        private final AtomicLong counter = new AtomicLong(0);
        private volatile long windowStart = System.currentTimeMillis();

        QuotaTracker(int maxPerMinute) { this.maxPerMinute = maxPerMinute; }

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            if (now - windowStart > 60_000) {
                windowStart = now;
                counter.set(0);
            }
            return counter.incrementAndGet() <= maxPerMinute;
        }
    }
}
