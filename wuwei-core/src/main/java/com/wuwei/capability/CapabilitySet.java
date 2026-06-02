package com.wuwei.capability;

import com.wuwei.bus.EventBus;
import com.wuwei.bus.event.KernelEvent;
import com.wuwei.skill.SkillManifest;
import com.wuwei.store.SkillStateStore;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wraps all capabilities for a Skill as GraalJS ProxyObjects.
 * Injected into the GraalJS Context's bindings as the "capability" object.
 *
 * Supports dynamic injection/NoOp at runtime via {@link #injectCapability}
 * and {@link #injectNoop}, used by the permission.gate flow (W8).
 */
public class CapabilitySet {

    private static final Logger log = LoggerFactory.getLogger(CapabilitySet.class);

    private final Map<String, Object> caps = new LinkedHashMap<>();
    private final List<Object> pendingPatches = new ArrayList<>();
    private final Map<String, Object> dataCache = new LinkedHashMap<>();

    // Dependencies kept for runtime injection
    private final String skillId;
    private final SkillStateStore store;
    private final NetworkCapability networkCap;
    private final FileCapability fileCap;
    private final AiCapability aiCap;
    private final CryptoCapability cryptoCap;
    private final DatabaseCapability databaseCap;
    private final WebSearchCapability webSearchCap;
    private final EventBus eventBus;
    private final CapabilityManager capManager;
    private final SkillManifest manifest;

    private CapabilitySet(String skillId, SkillStateStore store,
                          NetworkCapability networkCap, FileCapability fileCap,
                          AiCapability aiCap,
                          CryptoCapability cryptoCap, DatabaseCapability databaseCap,
                          WebSearchCapability webSearchCap,
                          EventBus eventBus, CapabilityManager capManager,
                          SkillManifest manifest) {
        this.skillId = skillId;
        this.store = store;
        this.networkCap = networkCap;
        this.fileCap = fileCap;
        this.aiCap = aiCap;
        this.cryptoCap = cryptoCap;
        this.databaseCap = databaseCap;
        this.webSearchCap = webSearchCap;
        this.eventBus = eventBus;
        this.capManager = capManager;
        this.manifest = manifest;
    }

    public static CapabilitySet build(SkillManifest manifest, SkillStateStore store,
                                       NetworkCapability networkCap,
                                       FileCapability fileCap,
                                       AiCapability aiCap,
                                       CryptoCapability cryptoCap,
                                       DatabaseCapability databaseCap,
                                       WebSearchCapability webSearchCap,
                                       EventBus eventBus,
                                       CapabilityManager capManager) {
        String skillId = manifest.id();
        CapabilitySet set = new CapabilitySet(skillId, store, networkCap, fileCap, aiCap,
            cryptoCap, databaseCap, webSearchCap, eventBus, capManager, manifest);

        // ── storage capability ──────────────────────────────────
        if (manifest.hasCapability("storage")) {
            set.caps.put("storage", set.buildStorage());
        }

        // ── data capability (DataModel) ─────────────────────────
        set.caps.put("data", set.buildData());

        // ── ui capability ───────────────────────────────────────
        set.caps.put("ui", set.buildUi());

        // ── network capability ──────────────────────────────────
        if (manifest.hasCapability("network")) {
            set.caps.put("network", networkCap.forSkill(manifest));
        }

        // ── ai capability (LLM search/retrieval) ─────────────────
        if (manifest.hasCapability("ai")) {
            set.caps.put("ai", aiCap.forSkill(skillId));
        }

        // ── file capability ─────────────────────────────────────
        if (manifest.hasCapability("file")) {
            set.caps.put("file", fileCap.forSkill(manifest));
        }

        // ── crypto capability ──────────────────────────────────
        if (manifest.hasCapability("crypto")) {
            set.caps.put("crypto", cryptoCap.forSkill(skillId));
        }

        // ── database capability ────────────────────────────────
        if (manifest.hasCapability("database")) {
            set.caps.put("db", databaseCap.forSkill(skillId));
        }

        // ── websearch capability ───────────────────────────────
        if (manifest.hasCapability("websearch")) {
            set.caps.put("websearch", webSearchCap.forSkill(skillId));
        }

        // ── os capability ───────────────────────────────────────
        if (manifest.hasCapability("os")) {
            set.caps.put("os", set.buildOs());
        }

        // ── events capability ───────────────────────────────────
        set.caps.put("events", set.buildEvents());

        // ── permission capability ───────────────────────────────
        set.caps.put("permission", set.buildPermission());

        return set;
    }

    // ── Runtime injection ──────────────────────────────────────────

    public List<String> getNetworkAllowlist() {
        return manifest.getNetworkAllowlist();
    }

    public void injectCapability(String capName) {
        Object impl = switch (capName) {
            case "storage" -> buildStorage();
            case "network" -> networkCap.forSkill(manifest);
            case "file" -> fileCap.forSkill(manifest);
            case "os" -> buildOs();
            case "ai" -> aiCap.forSkill(skillId);
            case "crypto" -> cryptoCap.forSkill(skillId);
            case "database", "db" -> databaseCap.forSkill(skillId);
            case "websearch" -> webSearchCap.forSkill(skillId);
            default -> { log.warn("Unknown capability: {}", capName); yield null; }
        };
        if (impl != null) {
            caps.put(capName, impl);
            log.info("Capability injected: {}::{}", skillId, capName);
        }
    }

    public void injectNoop(String capName) {
        ProxyObject noop = new ProxyObject() {
            @Override
            public Object getMember(String key) {
                // Return a silent no-op for any function call
                return (ProxyExecutable) args -> {
                    log.debug("NoOp: {}::{} {}() called — permission denied", skillId, capName, key);
                    return null;
                };
            }
            @Override
            public boolean hasMember(String key) { return true; }
            @Override
            public Set<String> getMemberKeys() { return Set.of(); }
            @Override
            public void putMember(String key, Value value) {}
            @Override
            public boolean removeMember(String key) { return false; }
        };
        caps.put(capName, noop);
        log.info("Capability NoOp injected: {}::{}", skillId, capName);
    }

    // ── Capability builders ────────────────────────────────────────

    private ProxyObject buildStorage() {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                return switch (key) {
                    case "get" -> (ProxyExecutable) args ->
                        store.get(skillId, args[0].asString());
                    case "put" -> (ProxyExecutable) args -> {
                        store.put(skillId, args[0].asString(), args[1].asString());
                        return null;
                    };
                    case "delete" -> (ProxyExecutable) args -> {
                        store.delete(skillId, args[0].asString());
                        return null;
                    };
                    default -> null;
                };
            }
            @Override
            public boolean hasMember(String key) {
                return Set.of("get", "put", "delete").contains(key);
            }
            @Override
            public Set<String> getMemberKeys() { return Set.of("get", "put", "delete"); }
            @Override
            public void putMember(String key, Value value) {}
            @Override
            public boolean removeMember(String key) { return false; }
        };
    }

    private final Map<String, Object> uiValueCache = new LinkedHashMap<>();

    private ProxyObject buildUi() {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                return switch (key) {
                    case "set" -> (ProxyExecutable) args -> {
                        String id = args[0].asString();
                        String prop = args[1].asString();
                        Object rawValue = unwrapValue(args[2]);
                        // Emit A2UI-native component fragment.
                        // DynamicString = plain string (static) OR { path: "/..." } (dynamic).
                        // NO literalString wrapper — that is NOT A2UI protocol.
                        pendingPatches.add(Map.of(
                            "id", id,
                            prop, rawValue
                        ));
                        // Cache raw value so ui.get can return it
                        uiValueCache.put(id + "." + prop, rawValue);
                        log.debug("ui.set {} {} {} = {}", skillId, id, prop, rawValue);
                        return null;
                    };
                    case "get" -> (ProxyExecutable) args -> {
                        String id = args[0].asString();
                        String prop = args[1].asString();
                        Object cached = uiValueCache.get(id + "." + prop);
                        return cached != null ? cached : "";
                    };
                    case "render" -> (ProxyExecutable) args -> {
                        // A2UI surfaceUpdate: replace entire component tree (page switching)
                        Object rawComponents = unwrapValue(args[0]);
                        pendingPatches.add(Map.of("surfaceUpdate", Map.of("components", rawComponents)));
                        return null;
                    };
                    default -> null;
                };
            }
            @Override
            public boolean hasMember(String key) { return Set.of("get", "set", "render").contains(key); }
            @Override
            public Set<String> getMemberKeys() { return Set.of("get", "set", "render"); }
            @Override
            public void putMember(String key, Value value) {}
            @Override
            public boolean removeMember(String key) { return false; }
        };
    }

    private ProxyObject buildData() {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                return switch (key) {
                    case "set" -> (ProxyExecutable) args -> {
                        String path = args[0].asString();
                        Object value = unwrapValue(args[1]);
                        dataCache.put(path, value);
                        // Generate a dataModelUpdate patch
                        pendingPatches.add(Map.of(
                            "type", "data",
                            "path", path,
                            "value", value
                        ));
                        log.debug("data.set {} = {}", path, value);
                        return null;
                    };
                    case "get" -> (ProxyExecutable) args -> {
                        String path = args[0].asString();
                        return dataCache.getOrDefault(path, "");
                    };
                    default -> null;
                };
            }
            @Override
            public boolean hasMember(String key) { return Set.of("get", "set").contains(key); }
            @Override
            public Set<String> getMemberKeys() { return Set.of("get", "set"); }
            @Override
            public void putMember(String key, Value value) {}
            @Override
            public boolean removeMember(String key) { return false; }
        };
    }

    private ProxyObject buildOs() {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                if ("notify".equals(key)) {
                    return (ProxyExecutable) args -> {
                        String title = args[0].asString();
                        String body = args[1].asString();
                        log.info("os.notify: {} - {}", title, body);
                        eventBus.publish(new KernelEvent.SystemNotify(title, body));
                        return null;
                    };
                }
                return null;
            }
            @Override
            public boolean hasMember(String key) { return "notify".equals(key); }
            @Override
            public Set<String> getMemberKeys() { return Set.of("notify"); }
            @Override
            public void putMember(String key, Value value) {}
            @Override
            public boolean removeMember(String key) { return false; }
        };
    }

    private ProxyObject buildEvents() {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                if ("emit".equals(key)) {
                    return (ProxyExecutable) args -> {
                        log.info("events.emit: {} payload={}", args[0].asString(), args[1]);
                        return null;
                    };
                }
                return null;
            }
            @Override
            public boolean hasMember(String key) { return "emit".equals(key); }
            @Override
            public Set<String> getMemberKeys() { return Set.of("emit"); }
            @Override
            public void putMember(String key, Value value) {}
            @Override
            public boolean removeMember(String key) { return false; }
        };
    }

    private ProxyObject buildPermission() {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                return switch (key) {
                    case "check" -> (ProxyExecutable) args -> {
                        String capName = args[0].asString();
                        return manifest.hasCapability(capName);
                    };
                    case "request" -> (ProxyExecutable) args -> {
                        String capName = args[0].asString();
                        String reason = args.length > 1 ? args[1].asString() : "";
                        log.info("permission.request: {} cap={} reason={}", skillId, capName, reason);
                        return capManager.requestPermission(skillId, capName, reason);
                    };
                    default -> null;
                };
            }
            @Override
            public boolean hasMember(String key) {
                return Set.of("check", "request").contains(key);
            }
            @Override
            public Set<String> getMemberKeys() { return Set.of("check", "request"); }
            @Override
            public void putMember(String key, Value value) {}
            @Override
            public boolean removeMember(String key) { return false; }
        };
    }

    // ── Streaming ────────────────────────────────────────────────────

    /** Register the skill thread's enqueue for ai.askStream callbacks. */
    public void registerStreamEnqueue(java.util.function.Consumer<Runnable> enqueue) {
        aiCap.registerEnqueue(skillId, enqueue);
    }

    /** Unregister on skill unload. */
    public void unregisterStreamEnqueue() {
        aiCap.unregisterEnqueue(skillId);
    }

    // ── Patches ─────────────────────────────────────────────────────

    /** Drain and clear the pending UI patches accumulated by ui.set(). */
    public List<Object> drainPatches() {
        List<Object> patches = new ArrayList<>(pendingPatches);
        pendingPatches.clear();
        return patches;
    }

    public Value toProxyObject() {
        return Value.asValue(new ProxyObject() {
            @Override
            public Object getMember(String key) {
                return caps.get(key);
            }
            @Override
            public boolean hasMember(String key) {
                return caps.containsKey(key);
            }
            @Override
            public Set<String> getMemberKeys() {
                return caps.keySet();
            }
            @Override
            public void putMember(String key, Value value) {}
            @Override
            public boolean removeMember(String key) { return false; }
        });
    }

    // ── helpers ──────────────────────────────────────────────────

    private static Object unwrapValue(Value v) {
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) {
            double d = v.asDouble();
            if (d == Math.floor(d) && !Double.isInfinite(d)) return (long) d;
            return d;
        }
        if (v.isString()) return v.asString();
        // JS array → Java List
        if (v.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            for (long i = 0; i < v.getArraySize(); i++) {
                list.add(unwrapValue(v.getArrayElement(i)));
            }
            return list;
        }
        // A2UI value objects like { literalString: "..." } or { path: "/..." }
        if (v.hasMembers()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : v.getMemberKeys()) {
                map.put(key, unwrapValue(v.getMember(key)));
            }
            return map;
        }
        return v.asString();
    }
}
