package com.wuwei.capability;

import com.wuwei.gate.GateException;
import org.springframework.stereotype.Component;
import com.wuwei.skill.SkillManifest;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controlled network access for Skills.
 * Uses JDK built-in HttpClient instead of Spring WebClient.
 */
@Component
public class NetworkCapability {

    private static final Logger log = LoggerFactory.getLogger(NetworkCapability.class);

    private final HttpClient httpClient;

    static final List<String> BLOCKED_PREFIXES = List.of(
        "http://127.", "https://127.",
        "http://localhost", "https://localhost",
        "http://0.0.0.0", "https://0.0.0.0",
        "http://[::1]", "https://[::1]",
        "http://10.", "https://10.",
        "http://192.168.", "https://192.168.",
        "http://172.16.", "http://172.17.", "http://172.18.", "http://172.19.",
        "http://172.20.", "http://172.21.", "http://172.22.", "http://172.23.",
        "http://172.24.", "http://172.25.", "http://172.26.", "http://172.27.",
        "http://172.28.", "http://172.29.", "http://172.30.", "http://172.31.",
        "http://169.254.", "https://169.254.",
        "http://100.100.100.", "https://100.100.100.",
        "http://metadata.google.", "https://metadata.google."
    );

    private final Map<String, QuotaTracker> quotas = new ConcurrentHashMap<>();

    public NetworkCapability() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public ProxyObject forSkill(SkillManifest manifest) {
        String skillId = manifest.id();
        List<String> allowlist = manifest.getNetworkAllowlist();

        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                if (!"fetch".equals(key)) return null;
                return (ProxyExecutable) args -> {
                    Value req = args[0];
                    String url = req.getMember("url").asString();
                    String method = req.hasMember("method")
                        ? req.getMember("method").asString().toUpperCase()
                        : "GET";
                    String body = req.hasMember("body")
                        ? req.getMember("body").asString()
                        : null;
                    return executeRequest(skillId, url, method, body, allowlist);
                };
            }

            @Override
            public boolean hasMember(String key) { return "fetch".equals(key); }

            @Override
            public Set<String> getMemberKeys() { return Set.of("fetch"); }

            @Override
            public void putMember(String key, Value value) {}

            @Override
            public boolean removeMember(String key) { return false; }
        };
    }

    public Object executeRequest(String skillId, String url, String method,
                          String body, List<String> allowlist) {
        // 1. Blacklist check
        String lower = url.toLowerCase();
        for (String blocked : BLOCKED_PREFIXES) {
            if (lower.startsWith(blocked)) {
                throw new GateException("NETWORK_BLOCKED",
                    "禁止访问受保护的地址: " + url);
            }
        }

        // 2. Whitelist check
        if (!allowlist.isEmpty()) {
            boolean allowed = allowlist.stream().anyMatch(url::startsWith);
            if (!allowed) {
                throw new GateException("NETWORK_BLOCKED",
                    "URL 不在白名单内: " + url + "\n已声明白名单: " + allowlist);
            }
        }

        // 3. Quota check
        QuotaTracker tracker = quotas.computeIfAbsent(skillId,
            k -> new QuotaTracker(100));
        if (!tracker.tryAcquire()) {
            throw new GateException("QUOTA_EXCEEDED",
                "网络请求频率超限（100/min），请稍后重试");
        }

        // 4. Random jitter (anti-timing side-channel)
        int jitter = ThreadLocalRandom.current().nextInt(0, 50);
        if (jitter > 0) {
            try { Thread.sleep(jitter); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // 5. Execute request using JDK HttpClient
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Wuwei/0.0.1-beta")
                .timeout(Duration.ofSeconds(10));

            switch (method) {
                case "GET" -> reqBuilder.GET();
                case "DELETE" -> reqBuilder.DELETE();
                case "POST", "PUT" -> {
                    String bodyStr = body != null ? body : "";
                    reqBuilder.method(method, HttpRequest.BodyPublishers.ofString(bodyStr));
                }
                default -> reqBuilder.method(method,
                    body != null ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            String responseBody = response.body() != null ? response.body() : "";

            log.info("network.fetch {} {} {} -> {} ({} bytes)",
                skillId, method, url, status, responseBody.length());

            return new ProxyObject() {
                @Override public Object getMember(String key) {
                    return switch (key) {
                        case "status" -> status;
                        case "body" -> responseBody;
                        default -> null;
                    };
                }
                @Override public boolean hasMember(String key) {
                    return Set.of("status", "body").contains(key);
                }
                @Override public Set<String> getMemberKeys() { return Set.of("status", "body"); }
                @Override public void putMember(String key, Value value) {}
                @Override public boolean removeMember(String key) { return false; }
            };

        } catch (GateException e) {
            throw e;
        } catch (Exception e) {
            log.error("network.fetch failed for {}: {} {} — {}", skillId, method, url, e.getMessage());
            throw new RuntimeException("Network request failed: " + e.getMessage(), e);
        }
    }

    private static class QuotaTracker {
        private final int maxPerMinute;
        private final AtomicLong counter = new AtomicLong(0);
        private volatile long windowStart = System.currentTimeMillis();

        QuotaTracker(int maxPerMinute) {
            this.maxPerMinute = maxPerMinute;
        }

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
