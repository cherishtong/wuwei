package com.wuwei.capability;

import com.wuwei.bus.EventBus;
import com.wuwei.bus.event.KernelEvent;
import com.wuwei.skill.SkillManifest;
import com.wuwei.store.SkillStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Factory that builds and injects a CapabilitySet for a Skill
 * based on its declared capabilities in skill.json.
 * Also manages dynamic permission requests and capability revocation.
 */
public class CapabilityManager {

    private static final Logger log = LoggerFactory.getLogger(CapabilityManager.class);

    private final SkillStateStore stateStore;
    private final NetworkCapability networkCap;
    private final FileCapability fileCap;
    private final AiCapability aiCap;
    private final CryptoCapability cryptoCap;
    private final DatabaseCapability databaseCap;
    private final WebSearchCapability webSearchCap;
    private final EventBus eventBus;

    /** Pending gate requests: key = "skillId:capName", value = CompletableFuture to complete */
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingGates = new ConcurrentHashMap<>();

    /** Active CapabilitySets keyed by skillId — for runtime injection/NoOp */
    private final ConcurrentHashMap<String, CapabilitySet> activeCapSets = new ConcurrentHashMap<>();

    /** Thread ID associated with each skill — set by SkillManager on activate */
    private final ConcurrentHashMap<String, String> skillThreadMap = new ConcurrentHashMap<>();

    public CapabilityManager(SkillStateStore stateStore, NetworkCapability networkCap,
                             FileCapability fileCap, AiCapability aiCap,
                             CryptoCapability cryptoCap, DatabaseCapability databaseCap,
                             WebSearchCapability webSearchCap,
                             EventBus eventBus) {
        this.stateStore = stateStore;
        this.networkCap = networkCap;
        this.fileCap = fileCap;
        this.aiCap = aiCap;
        this.cryptoCap = cryptoCap;
        this.databaseCap = databaseCap;
        this.webSearchCap = webSearchCap;
        this.eventBus = eventBus;
    }

    /**
     * Build the complete capability set for a skill and register it for
     * potential runtime injection.
     */
    public CapabilitySet inject(SkillManifest manifest) {
        CapabilitySet capSet = CapabilitySet.build(manifest, stateStore, networkCap, fileCap,
            aiCap, cryptoCap, databaseCap, webSearchCap, eventBus, this);
        activeCapSets.put(manifest.id(), capSet);
        return capSet;
    }

    /** Remove the capability set when a skill is unloaded. */
    public void remove(String skillId) {
        activeCapSets.remove(skillId);
        skillThreadMap.remove(skillId);
    }

    /** Track which thread a skill is active in. Called by SkillManager. */
    public void setSkillThreadId(String skillId, String threadId) {
        if (threadId != null && !threadId.isEmpty()) {
            skillThreadMap.put(skillId, threadId);
        } else {
            skillThreadMap.remove(skillId);
        }
    }

    // ── Dynamic Permission ──────────────────────────────────────────

    /**
     * Called synchronously from a Skill's capability.permission.request().
     * Blocks the GraalJS thread until the user responds via confirm-gate WS message,
     * with a 5-minute timeout.
     */
    public boolean requestPermission(String skillId, String capName, String reason) {
        String gateKey = skillId + ":" + capName;
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingGates.put(gateKey, future);

        String threadId = skillThreadMap.get(skillId);
        eventBus.publish(new KernelEvent.GateRequest(skillId, threadId, capName, reason));
        log.info("Gate request published: {} cap={}", skillId, capName);

        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.warn("Gate request timed out: {} cap={}", skillId, capName);
            pendingGates.remove(gateKey);
            return false;
        } catch (Exception e) {
            log.warn("Gate request interrupted: {} cap={}", skillId, capName);
            pendingGates.remove(gateKey);
            return false;
        }
    }

    /**
     * Called from MessageRouter when the user responds to a gate dialog.
     * Approves → inject real capability. Denies → inject NoOp stub.
     */
    public void resolveGate(String skillId, String capName, boolean approved) {
        String gateKey = skillId + ":" + capName;
        CompletableFuture<Boolean> future = pendingGates.remove(gateKey);
        if (future != null) {
            // Inject or NoOp before completing the future so the handler sees the result
            CapabilitySet capSet = activeCapSets.get(skillId);
            if (capSet != null) {
                if (approved) {
                    capSet.injectCapability(capName);
                } else {
                    capSet.injectNoop(capName);
                }
            } else {
                log.warn("No active CapabilitySet for {}, skipping injection", skillId);
            }
            future.complete(approved);
            if (approved) {
                log.info("Gate resolved: {} cap={} GRANTED", skillId, capName);
            } else {
                log.warn("Gate resolved: {} cap={} DENIED — capability NoOp injected", skillId, capName);
                eventBus.publish(new KernelEvent.SystemNotify(
                    "权限已拒绝", skillId + " 的 " + capName + " 能力已被禁用"));
            }
        } else {
            log.warn("No pending gate for {}:{}, response ignored", skillId, capName);
        }
    }

    // ── Revoke ───────────────────────────────────────────────────────

    /**
     * Revoke a capability from a running Skill by replacing it with a NoOp.
     */
    public void revoke(String skillId, String capName) {
        log.info("Capability revoked: {} cap={}", skillId, capName);
        CapabilitySet capSet = activeCapSets.get(skillId);
        if (capSet != null) {
            capSet.injectNoop(capName);
        }
        eventBus.publish(new KernelEvent.KernelError(skillId, "CAPABILITY_REVOKED",
            "能力 " + capName + " 已被撤销"));
    }

    /**
     * Cancel any pending gate requests for a skill (e.g., on uninstall).
     */
    public void cancelPendingGates(String skillId) {
        pendingGates.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(skillId + ":")) {
                entry.getValue().complete(false);
                return true;
            }
            return false;
        });
        activeCapSets.remove(skillId);
    }

    // ── Proxy execution (browser-js capability proxy) ─────────────

    /**
     * Execute a capability call directly for browser-js skills.
     * Bypasses GraalJS ProxyObjects — returns plain Java objects for JSON serialization.
     */
    @SuppressWarnings("unchecked")
    public Object executeProxy(String skillId, String capName, String method, List<Object> args) {
        CapabilitySet capSet = activeCapSets.get(skillId);
        if (capSet == null) {
            return Map.of("error", "No capability set for " + skillId);
        }
        try {
            return switch (capName) {
                case "storage" -> executeStorageProxy(skillId, method, args);
                case "network" -> executeNetworkProxy(skillId, method, args);
                case "ai" -> executeAiProxy(skillId, method, args);
                case "file" -> executeFileProxy(skillId, method, args);
                case "os" -> executeOsProxy(method, args);
                case "crypto" -> cryptoCap.executeProxy(method, args);
                case "db" -> databaseCap.executeProxy(skillId, method, args);
                case "websearch" -> webSearchCap.executeProxy(skillId, method, args);
                default -> Map.of("error", "Unknown capability: " + capName);
            };
        } catch (Exception e) {
            log.error("Proxy execution failed: {}.{}({})", capName, method, args, e);
            return Map.of("error", e.getMessage());
        }
    }

    private Object executeStorageProxy(String skillId, String method, List<Object> args) {
        return switch (method) {
            case "get" -> stateStore.get(skillId, (String) args.get(0));
            case "put" -> {
                stateStore.put(skillId, (String) args.get(0), (String) args.get(1));
                yield null;
            }
            case "delete" -> {
                stateStore.delete(skillId, (String) args.get(0));
                yield null;
            }
            default -> Map.of("error", "Unknown storage method: " + method);
        };
    }

    private Object executeNetworkProxy(String skillId, String method, List<Object> args) {
        if ("fetch".equals(method)) {
            Map<String, Object> opts = (Map<String, Object>) args.get(0);
            String url = (String) opts.get("url");
            // Build a skill manifest-like allowlist lookup for the actual call
            CapabilitySet capSet = activeCapSets.get(skillId);
            List<String> allowlist = capSet != null ? capSet.getNetworkAllowlist() : List.of();
            var result = networkCap.executeRequest(
                skillId, url,
                opts.getOrDefault("method", "GET").toString().toUpperCase(),
                opts.get("body") != null ? opts.get("body").toString() : null,
                allowlist
            );
            // result is a ProxyObject with status/body — unwrap it
            if (result instanceof org.graalvm.polyglot.proxy.ProxyObject po) {
                Object status = po.getMember("status");
                Object body = po.getMember("body");
                Map<String, Object> plain = new LinkedHashMap<>();
                plain.put("status", status);
                plain.put("body", body);
                return plain;
            }
            return result;
        }
        return Map.of("error", "Unknown network method: " + method);
    }

    private Object executeAiProxy(String skillId, String method, List<Object> args) {
        if ("ask".equals(method)) {
            String prompt = (String) args.get(0);
            var result = aiCap.executeAsk(skillId, prompt);
            if (result instanceof org.graalvm.polyglot.proxy.ProxyObject po) {
                Map<String, Object> plain = new LinkedHashMap<>();
                plain.put("status", po.getMember("status"));
                plain.put("body", po.getMember("body"));
                return plain;
            }
            return result;
        }
        return Map.of("error", "Unknown ai method: " + method);
    }

    private Object executeFileProxy(String skillId, String method, List<Object> args) {
        // Use FileCapability via a direct helper — it creates sandbox-rooted paths
        String home = System.getProperty("user.home");
        java.nio.file.Path sandboxRoot = java.nio.file.Paths.get(
            home, ".wuwei", "skills", skillId, "phenotype", "sandbox");
        try {
            java.nio.file.Files.createDirectories(sandboxRoot);
        } catch (java.io.IOException ignored) {}

        return switch (method) {
            case "read" -> {
                java.nio.file.Path p = FileCapability.resolveSandboxed(skillId, sandboxRoot, (String) args.get(0));
                try { yield java.nio.file.Files.readString(p); }
                catch (java.io.IOException e) { yield Map.of("error", e.getMessage()); }
            }
            case "write" -> {
                java.nio.file.Path p = FileCapability.resolveSandboxed(skillId, sandboxRoot, (String) args.get(0));
                try {
                    java.nio.file.Files.createDirectories(p.getParent());
                    java.nio.file.Files.writeString(p, (String) args.get(1));
                    yield null;
                } catch (java.io.IOException e) { yield Map.of("error", e.getMessage()); }
            }
            case "list" -> {
                java.nio.file.Path dir = FileCapability.resolveSandboxed(skillId, sandboxRoot, (String) args.get(0));
                try (var stream = java.nio.file.Files.list(dir)) {
                    yield stream.map(java.nio.file.Path::getFileName)
                        .map(java.nio.file.Path::toString)
                        .toList();
                } catch (java.io.IOException e) { yield List.of(); }
            }
            case "delete" -> {
                java.nio.file.Path p = FileCapability.resolveSandboxed(skillId, sandboxRoot, (String) args.get(0));
                try { java.nio.file.Files.delete(p); yield null; }
                catch (java.io.IOException e) { yield Map.of("error", e.getMessage()); }
            }
            default -> Map.of("error", "Unknown file method: " + method);
        };
    }

    private Object executeOsProxy(String method, List<Object> args) {
        if ("notify".equals(method)) {
            String title = (String) args.get(0);
            String body = (String) args.get(1);
            eventBus.publish(new KernelEvent.SystemNotify(title, body));
            return null;
        }
        return Map.of("error", "Unknown os method: " + method);
    }
}
