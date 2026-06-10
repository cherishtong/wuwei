package com.wuwei.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.bus.event.KernelEvent;
import com.wuwei.store.OpLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final ObjectMapper mapper;
    private final OpLogService opLog;
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    // ── Rate limiting ──────────────────────────────────────────
    private static final double MAX_PER_SECOND = 10.0;
    private static final long BURST_CAPACITY = 20;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private volatile boolean backpressureActive = false;
    private volatile boolean rateLimitEnabled = false;

    public EventBus(ObjectMapper mapper, OpLogService opLog) {
        this.mapper = mapper;
        this.opLog = opLog;
    }

    // ── Session management ─────────────────────────────────────

    public void addSession(WebSocketSession session) { sessions.add(session); }
    public void removeSession(WebSocketSession session) { sessions.remove(session); }
    public int getSessionCount() { return sessions.size(); }

    // ── Rate limit toggle ──────────────────────────────────────

    public void setRateLimitEnabled(boolean enabled) {
        this.rateLimitEnabled = enabled;
        log.info("Rate limit {}", enabled ? "enabled" : "disabled");
    }
    public boolean isRateLimitEnabled() { return rateLimitEnabled; }

    // ── Publish to all connected WebSocket clients ──────────────

    public void publish(KernelEvent event) {
        if (rateLimitEnabled && !isCritical(event) && !tryAcquireToken(event)) {
            var type = toKebabCase(event.getClass().getSimpleName());
            var skillId = getSkillId(event);
            log.warn("Backpressure: dropping event type={} skill={}", type, skillId);
            if (!backpressureActive) {
                backpressureActive = true;
                try {
                    var notify = Map.of("type", "system-notify",
                        "title", "背压告警",
                        "body", "事件广播速率超过 10/s，部分事件被丢弃");
                    broadcastRaw(mapper.writeValueAsString(notify));
                } catch (Exception ignored) {}
            }
            return;
        }
        if (backpressureActive) {
            backpressureActive = false;
            log.info("Backpressure resolved");
        }

        String json = serialize(event);
        broadcastRaw(json);
        opLog.record(event);
    }

    /** Publish to a single WebSocket session. */
    public void publishTo(WebSocketSession session, KernelEvent event) {
        String json = serialize(event);
        sendTo(session, json);
        opLog.record(event);
    }

    // ── Raw broadcast ──────────────────────────────────────────

    public void broadcastRaw(String json) {
        for (var session : sessions) {
            sendTo(session, json);
        }
    }

    public void sendTo(WebSocketSession session, String json) {
        if (session.isOpen()) {
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (IOException e) {
                log.warn("Failed to send to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    // ── Serialization ──────────────────────────────────────────

    public String serialize(KernelEvent event) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", toKebabCase(event.getClass().getSimpleName()));
            map.put("ns", namespaceOf(event));

            for (var rc : event.getClass().getRecordComponents()) {
                try {
                    Object value = rc.getAccessor().invoke(event);
                    map.put(rc.getName(), value);
                } catch (Exception e) {
                    log.warn("Failed to read component {}: {}", rc.getName(), e.getMessage());
                }
            }
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Event serialization failed", e);
        }
    }

    public static String namespaceOf(KernelEvent event) {
        return switch (event) {
            case KernelEvent.SkillActivated ignored -> "ui";
            case KernelEvent.A2uiPatch ignored -> "ui";
            case KernelEvent.SkillDeactivated ignored -> "ui";
            case KernelEvent.EventAck ignored -> "ui";
            case KernelEvent.SkillHandoff ignored -> "ui";
            case KernelEvent.SkillLog ignored -> "log";
            case KernelEvent.PiLog ignored -> "log";
            case KernelEvent.RepairAttempt ignored -> "log";
            case KernelEvent.PlanStep ignored -> "log";
            default -> "sys";
        };
    }

    // ── Rate limiting internals ────────────────────────────────

    private static boolean isCritical(KernelEvent event) {
        return switch (event) {
            case KernelEvent.KernelReady ignored -> true;
            case KernelEvent.SkillList ignored -> true;
            case KernelEvent.SkillActivated ignored -> true;
            case KernelEvent.SkillDeactivated ignored -> true;
            case KernelEvent.KernelError ignored -> true;
            case KernelEvent.GuardianWarning ignored -> true;
            case KernelEvent.SystemNotify ignored -> true;
            default -> false;
        };
    }

    private boolean tryAcquireToken(KernelEvent event) {
        String key = getSkillId(event);
        return buckets.computeIfAbsent(key, k -> new TokenBucket()).tryConsume();
    }

    private static String getSkillId(KernelEvent event) {
        return switch (event) {
            case KernelEvent.A2uiPatch(var skillId, var tid, var p) -> skillId;
            case KernelEvent.EventAck(var skillId, var eid, var st, var lat, var p) -> skillId;
            case KernelEvent.SkillLog(var skillId, var lvl, var msg) -> skillId;
            default -> "__global__";
        };
    }

    static String toKebabCase(String camelCase) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('-');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── Token bucket ───────────────────────────────────────────

    private static class TokenBucket {
        private final AtomicLong tokens = new AtomicLong(BURST_CAPACITY);
        private final AtomicReference<Long> lastRefill = new AtomicReference<>(System.currentTimeMillis());

        boolean tryConsume() {
            long now = System.currentTimeMillis();
            long last = lastRefill.get();
            long elapsed = now - last;
            if (elapsed > 100) {
                long newTokens = (long) (elapsed * MAX_PER_SECOND / 1000.0);
                if (newTokens > 0 && lastRefill.compareAndSet(last, now)) {
                    long current = tokens.addAndGet(newTokens);
                    if (current > BURST_CAPACITY) tokens.set(BURST_CAPACITY);
                }
            }
            long t = tokens.get();
            while (t > 0) {
                if (tokens.compareAndSet(t, t - 1)) return true;
                t = tokens.get();
            }
            return false;
        }
    }
}
