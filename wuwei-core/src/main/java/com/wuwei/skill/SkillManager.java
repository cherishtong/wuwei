package com.wuwei.skill;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.a2ui.A2uiEngine;
import com.wuwei.bus.EventBus;
import com.wuwei.bus.event.KernelEvent;
import com.wuwei.capability.CapabilityManager;
import com.wuwei.capability.CapabilitySet;
import com.wuwei.gate.AstAuditor;
import com.wuwei.gate.EcosystemGuardian;
import com.wuwei.gate.GateException;
import com.wuwei.sandbox.RuntimePool;
import com.wuwei.sandbox.SkillRuntime;
import com.wuwei.snapshot.SnapshotService;
import com.wuwei.store.ConversationService;
import com.wuwei.store.SkillStateStore;
import com.wuwei.store.StoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Central Skill lifecycle coordinator.
 * Loads skills from disk, manages runtimes, routes events, broadcasts UI patches.
 */
@Component
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    private final RuntimePool runtimePool;
    private final A2uiEngine a2uiEngine;
    private final com.wuwei.rag.SkillIndexer skillIndexer;
    private final CapabilityManager capManager;
    private final StoreService storeService;
    private final SkillStateStore stateStore;
    private final EventBus eventBus;
    private final ObjectMapper mapper;
    private final AstAuditor astAuditor;
    private final EcosystemGuardian ecosystemGuardian;
    private final SnapshotService snapshotService;
    private final ConversationService conversationService;
    private volatile Consumer<String> onConvUpdate;

    private final ConcurrentHashMap<String, LoadedSkill> skills = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastModified = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> skillThreadMap = new ConcurrentHashMap<>();
    private final String skillsBaseDir;

    private static final int EVENT_TIMEOUT_SECONDS = 120; // 2 min — ai.ask / network.fetch may take time
    private static final int INIT_TIMEOUT_SECONDS = 300; // 5 min — onInit may block on permission.request
    private static final int POLL_INTERVAL_MS = 2000;

    public SkillManager(RuntimePool runtimePool, A2uiEngine a2uiEngine,
                        CapabilityManager capManager, StoreService storeService,
                        SkillStateStore stateStore, EventBus eventBus,
                        ObjectMapper mapper, AstAuditor astAuditor,
                        EcosystemGuardian ecosystemGuardian,
                        SnapshotService snapshotService,
                        ConversationService conversationService,
                        com.wuwei.rag.SkillIndexer skillIndexer) {
        this.runtimePool = runtimePool;
        this.a2uiEngine = a2uiEngine;
        this.capManager = capManager;
        this.storeService = storeService;
        this.stateStore = stateStore;
        this.eventBus = eventBus;
        this.mapper = mapper;
        this.astAuditor = astAuditor;
        this.ecosystemGuardian = ecosystemGuardian;
        this.snapshotService = snapshotService;
        this.conversationService = conversationService;
        this.skillIndexer = skillIndexer;

        String home = System.getProperty("user.home");
        this.skillsBaseDir = Paths.get(home, ".wuwei", "skills").toString();
    }

    public void setOnConvUpdate(Consumer<String> callback) {
        this.onConvUpdate = callback;
    }

    // ── Startup ────────────────────────────────────────────────────

    /**
     * Scan the skills directory and pre-load all valid skill folders.
     * Called explicitly from Main after all components are wired.
     */
    public void startupLoad() {
        Path dir = Path.of(skillsBaseDir);
        System.out.println("[SkillManager] skillsBaseDir=" + skillsBaseDir + " exists=" + Files.isDirectory(dir));
        if (!Files.isDirectory(dir)) {
            log.info("Skills directory not found, creating: {}", skillsBaseDir);
            System.out.println("[SkillManager] Skills directory not found, creating: " + skillsBaseDir);
            try {
                Files.createDirectories(dir);
            } catch (Exception e) {
                log.error("Failed to create skills directory", e);
            }
            return;
        }
        // Clean up stale gen staging directories left from crashed/interrupted generations.
        // new-XXXXXXXX-gen dirs: their skill.json has the real skill's ID, overwriting A2UI trees.
        // new-XXXXXXXX dirs (without -gen): stale stub skills with self-matching IDs (harmless but clutter).
        try (var staleEntries = Files.list(dir)) {
            staleEntries.filter(Files::isDirectory).forEach(skillDir -> {
                String name = skillDir.getFileName().toString();
                boolean isGenDir = name.startsWith("new-") && name.matches("^new-[0-9a-f]{6,12}-gen$");
                boolean isStubSkill = name.startsWith("new-") && name.matches("^new-[0-9a-f]{6,12}$");
                if (isGenDir || isStubSkill) {
                    try {
                        try (var walk = Files.walk(skillDir)) {
                            walk.sorted(java.util.Comparator.reverseOrder())
                                .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                        }
                        System.out.println("[SkillManager] Cleaned up stale gen dir: " + name);
                    } catch (Exception e) {
                        System.out.println("[SkillManager] Failed to clean gen dir " + name + ": " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            System.out.println("[SkillManager] Gen dir cleanup error: " + e.getMessage());
        }

        try (var entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(skillDir -> {
                String skillId = skillDir.getFileName().toString();
                try {
                    loadFromDirectory(skillDir);
                    // Register modification time to prevent file watcher from reactivating
                    Long latestMod = getLatestModified(skillDir);
                    if (latestMod != 0) lastModified.put(skillId, latestMod);
                    System.out.println("[SkillManager] Startup-loaded: " + skillId);
                    log.info("Startup-loaded skill: {}", skillId);
                } catch (Exception e) {
                    System.out.println("[SkillManager] FAILED to load " + skillId + ": " + e.getMessage());
                    log.error("Failed to startup-load skill {}: {}", skillId, e.getMessage());
                }
            });
        } catch (Exception e) {
            System.out.println("[SkillManager] Scan error: " + e.getMessage());
            log.error("Failed to scan skills directory", e);
        }
        System.out.println("[SkillManager] Skills loaded: " + skills.keySet());
        log.info("Skills loaded at startup: {}", skills.keySet());

        // Start file watcher for hot-reload
        startFileWatcher();
    }

    // ── Hot Reload ──────────────────────────────────────────────────

    private void startFileWatcher() {
        Thread watcher = new Thread(() -> {
            log.info("Skill file watcher started on {} (polling every {}ms)",
                skillsBaseDir, POLL_INTERVAL_MS);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }

                Path base = Paths.get(skillsBaseDir);
                if (!Files.isDirectory(base)) continue;

                try (var entries = Files.list(base)) {
                    entries.filter(Files::isDirectory).forEach(skillDir -> {
                        String id = skillDir.getFileName().toString();
                        if (!lastModified.containsKey(id)) {
                            // New directory detected — load it
                            try {
                                loadFromDirectory(skillDir);
                                activate(id);
                                System.out.println("[kernel] Watcher: loaded new skill " + id);
                            } catch (Exception ex) {
                                log.warn("Watcher: failed to load new skill {}: {}", id, ex.getMessage());
                            }
                        } else {
                            checkAndReload(skillDir);
                        }
                    });
                } catch (Exception e) {
                    log.warn("File watcher scan error: {}", e.getMessage());
                }
            }
            log.info("Skill file watcher stopped");
        }, "skill-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void checkAndReload(Path skillDir) {
        String skillId = skillDir.getFileName().toString();

        // Skip gen staging directories — they contain skill.json with a different id
        // and would overwrite real skill trees if loaded
        if (skillId.startsWith("new-") && skillId.endsWith("-gen")) {
            return;
        }

        try {
            Path manifestPath = skillDir.resolve("skill.json");
            if (!Files.exists(manifestPath)) {
                // Directory without skill.json — skip (might be staging or stale)
                if (skills.containsKey(skillId)) {
                    uninstall(skillId);
                }
                return;
            }
            long latestMod = getLatestModified(skillDir);
            Long prev = lastModified.put(skillId, latestMod);
            if (prev != null && prev.longValue() == latestMod) {
                return;
            }
            if (prev != null) {
                log.info("Hot-reload: {} changed (mod time {} -> {})", skillId, prev, latestMod);
                reloadSkill(skillDir);
            } else if (!skills.containsKey(skillId)) {
                // New skill directory discovered after startup
                log.info("Hot-reload: new skill discovered {}", skillId);
                reloadSkill(skillDir);
            }
        } catch (Exception e) {
            log.warn("Failed to check mod time for {}: {}", skillId, e.getMessage());
        }
    }

    private long getLatestModified(Path dir) throws Exception {
        long[] latest = {0};
        try (var stream = Files.walk(dir)) {
            stream
                .filter(Files::isRegularFile)
                .filter(f -> {
                    String pathStr = f.toString().replace('\\', '/');
                    return !pathStr.contains("/phenotype/");
                })
                .forEach(f -> {
                    try {
                        long mod = Files.getLastModifiedTime(f).toMillis();
                        if (mod > latest[0]) latest[0] = mod;
                    } catch (Exception ignored) {}
                });
        }
        return latest[0];
    }

    private void reloadSkill(Path skillDir) {
        String skillId = skillDir.getFileName().toString();

        Path manifestPath = skillDir.resolve("skill.json");
        if (!Files.exists(manifestPath)) {
            log.info("Hot-reload: {} has no skill.json, assuming deleted", skillId);
            if (skills.containsKey(skillId)) {
                uninstall(skillId);
            }
            return;
        }

        boolean wasActive = false;
        LoadedSkill old = skills.get(skillId);
        if (old != null && old.status() == SkillStatus.RUNNING) {
            wasActive = true;
        }

        if (old != null) {
            // Light unload: close runtime, keep state DB intact
            skills.remove(skillId);
            capManager.cancelPendingGates(skillId);
            try {
                if (old.runtime() instanceof SkillRuntime sr) sr.close();
                else if (old.runtime() instanceof BrowserSkillRuntime bsr) bsr.close();
            } catch (Exception e) { log.warn("Error closing runtime for {}: {}", skillId, e.getMessage()); }
            a2uiEngine.unregister(skillId);
            ecosystemGuardian.forget(skillId);
        }

        try {
            loadFromDirectory(skillDir);
            log.info("Hot-reload successful: {} {}", skillId, wasActive ? "(re-activating)" : "");

            if (wasActive) {
                final String id = skillId;
                new Thread(() -> activate(id), "activate-" + id).start();
            }

            eventBus.publish(new KernelEvent.SystemNotify(
                "Skill 已刷新", skillId + " 已热更新成功"));
        } catch (Exception e) {
            log.error("Hot-reload failed for {}: {}", skillId, e.getMessage());
            eventBus.publish(new KernelEvent.KernelError(skillId, "HOT_RELOAD_FAILED",
                "热更新失败: " + e.getMessage()));
        }
    }

    // ── Load / Activate ─────────────────────────────────────────────

    public LoadedSkill loadFromDirectory(Path skillDir) throws Exception {
        Path manifestPath = skillDir.resolve("skill.json");
        Path genomeDir = skillDir.resolve("genome");

        if (!Files.exists(manifestPath)) {
            throw new IllegalArgumentException("Missing skill.json in " + skillDir);
        }

        SkillManifest manifest = mapper.readValue(manifestPath.toFile(), SkillManifest.class);
        String skillId = manifest.id();
        String dirName = skillDir.getFileName().toString();

        // Safety: reject gen staging directories whose manifest id would collide
        // with a real skill. During startup, these overwrite the real skill's tree.
        if (!dirName.equals(skillId) && dirName.startsWith("new-") && dirName.endsWith("-gen")) {
            String msg = "Rejecting gen staging directory " + dirName
                + " (manifest id=" + skillId + " would overwrite real skill tree)";
            System.out.println("[loadFromDirectory] " + msg);
            log.warn(msg);
            throw new IllegalArgumentException(msg);
        }

        log.info("Loading skill {} from {}", skillId, skillDir);

        // ── UI: try ui/ directory first, fall back to ui.json ──
        Path uiDir = genomeDir.resolve("ui");
        Path uiPath = genomeDir.resolve("ui.json");
        String uiJson;
        JsonNode uiTree;

        if (Files.isDirectory(uiDir)) {
            // Multi-fragment mode: resolve $ref from ui/index.json
            uiTree = a2uiEngine.resolveUiRefs(uiDir);
            uiJson = mapper.writeValueAsString(uiTree);
            java.util.List<String> dfKeys = new java.util.ArrayList<>();
            var dfIter = ((com.fasterxml.jackson.databind.node.ObjectNode)uiTree).fieldNames();
            while (dfIter.hasNext()) dfKeys.add(dfIter.next());
            System.out.println("[loadFromDirectory] " + skillId + " uiDir mode, tree fieldNames=" + dfKeys + " hasComponents=" + uiTree.has("components"));
        } else if (Files.exists(uiPath)) {
            uiJson = Files.readString(uiPath);
            uiTree = mapper.readTree(uiJson);
            System.out.println("[loadFromDirectory] " + skillId + " ui.json mode, uiJsonLen=" + uiJson.length() + " tree=" + uiTree.getClass().getSimpleName() + " hasComponents=" + uiTree.has("components"));
        } else {
            uiJson = "{}";
            uiTree = mapper.readTree(uiJson);
            System.out.println("[loadFromDirectory] " + skillId + " no UI file, defaulting to {}");
        }

        // ── Handlers: try handlers/ directory first, fall back to handlers.js ──
        Path handlersDir = genomeDir.resolve("handlers");
        Path handlersPath = genomeDir.resolve("handlers.js");
        String handlersJs;
        Map<String, String> moduleFiles = null;

        if (Files.isDirectory(handlersDir)) {
            // Multi-file mode: index.js is entry point, rest are modules
            Map<String, String> files = walkJsFiles(handlersDir);
            handlersJs = files.get("index.js");
            if (handlersJs == null) {
                throw new IllegalArgumentException("handlers/index.js not found in " + handlersDir);
            }
            moduleFiles = files;
        } else if (Files.exists(handlersPath)) {
            handlersJs = Files.readString(handlersPath);
        } else {
            handlersJs = "";
        }

        // Snapshot restore removed — all state lives on disk in the skill directory.
        // Disk is the canonical source of truth, no need for recovery from SQLite.

        SkillGenome genome = new SkillGenome(uiJson, handlersJs, moduleFiles);

        // Gate static audit
        try {
            astAuditor.audit(manifest, genome);
        } catch (GateException e) {
            log.error("Gate audit failed for {}: [{}] {}", skillId, e.getCode(), e.getMessage());
            throw e;
        }

        // Ecosystem guardian check
        ecosystemGuardian.check(skillId, genome);

        // ── Handle runtime: "md" (sidebar.json + c.storage handlers) ──
        if ("md".equals(manifest.runtime())) {
            Path phenotypeDir = skillDir.resolve("phenotype");
            MdRuntime.copyAssets(genomeDir, phenotypeDir);
            uiTree = MdRuntime.buildUiTree(genomeDir);
            uiJson = mapper.writeValueAsString(uiTree);
            handlersJs = MdRuntime.buildHandlers(genomeDir);
            System.out.println("[loadFromDirectory] " + skillId + " md handlers len=" + handlersJs.length());
            a2uiEngine.register(skillId, uiTree);
            storeService.recordInstall(manifest);
            Consumer<List<Object>> flush = patches -> {
                var applied = a2uiEngine.applyPatches(skillId, patches);
                if (!applied.isEmpty()) { String tid = skillThreadMap.get(skillId); eventBus.publish(new KernelEvent.A2uiPatch(skillId, tid, applied)); }
            };
            CapabilitySet capSet = capManager.inject(manifest);
            Object runtime = runtimePool.create(manifest, handlersJs, capSet, flush);
            LoadedSkill mdSkill = new LoadedSkill(manifest, runtime, uiTree, SkillStatus.RUNNING);
            skills.put(skillId, mdSkill);
            log.info("Skill loaded (md): {}", skillId);
            if (skillIndexer != null) {
                skillIndexer.addQuick(skillId,
                    manifest.meta() != null && manifest.meta().getOrDefault("title", skillId) != null
                        ? manifest.meta().getOrDefault("title", skillId).toString() : skillId,
                    manifest.meta() != null && manifest.meta().getOrDefault("description", "") != null
                        ? manifest.meta().getOrDefault("description", "").toString() : "",
                    new ArrayList<>(manifest.capabilities() != null ? manifest.capabilities().keySet() : Set.of()));
            }
            return mdSkill;
        }

        // Build capabilities and create sandbox runtime
        CapabilitySet capSet = capManager.inject(manifest);

        Object runtime;
        if ("browser-js".equals(manifest.runtime())) {
            runtime = new BrowserSkillRuntime(manifest, handlersJs);
        } else {
            Consumer<List<Object>> flushPatches = patches -> {
                List<Object> applied = a2uiEngine.applyPatches(skillId, patches);
                if (!applied.isEmpty()) {
                    String tid = skillThreadMap.get(skillId);
                    eventBus.publish(new KernelEvent.A2uiPatch(skillId, tid, applied));
                }
            };
            if (moduleFiles != null && !moduleFiles.isEmpty()) {
                runtime = runtimePool.create(manifest, handlersJs, capSet, flushPatches, moduleFiles);
            } else {
                runtime = runtimePool.create(manifest, handlersJs, capSet, flushPatches);
            }
        }

        // Guard: if handlers are empty and skill already has valid handlers, skip (file watcher race)
        LoadedSkill existing = skills.get(skillId);
        if (handlersJs.isBlank() && existing != null && existing.runtime() instanceof SkillRuntime) {
            System.out.println("[loadFromDirectory] " + skillId + " skip empty-handler overwrite");
            return existing;
        }

        // Register UI tree
        a2uiEngine.register(skillId, uiTree);

        // Record in registry
        storeService.recordInstall(manifest);

        LoadedSkill skill = LoadedSkill.create(manifest, runtime, uiTree);
        skills.put(skillId, skill);
        log.info("Skill loaded: {} v{} ({})", skillId, manifest.version(), manifest.runtime());

        // Fire onInstall lifecycle hook (once, on first load)
        if (runtime instanceof SkillRuntime sr) {
            sr.callLifecycleHandler("onInstall");
        }
        // Index skill for RAG retrieval (quick: name + description only, no LLM call)
        if (skillIndexer != null) {
            String name = skillId;
            String desc = "";
            try {
                var meta = manifest.meta();
                if (meta != null) {
                    name = meta.getOrDefault("title", skillId).toString();
                    desc = meta.getOrDefault("description", "").toString();
                }
            } catch (Exception ignored) {}
            skillIndexer.addQuick(skillId, name, desc,
                new ArrayList<>(manifest.capabilities() != null ? manifest.capabilities().keySet() : Set.of()));
        }
        return skill;
    }

    public void activate(String skillId) {
        activate(skillId, null);
    }

    public void activate(String skillId, String threadId) {
        LoadedSkill skill = skills.get(skillId);
        if (skill == null) {
            log.warn("Activate requested for unknown skill: {}", skillId);
            eventBus.publish(new KernelEvent.KernelError(skillId, "SKILL_NOT_FOUND",
                "Skill not loaded: " + skillId));
            return;
        }

        if (threadId != null && !threadId.isEmpty()) {
            skillThreadMap.put(skillId, threadId);
            capManager.setSkillThreadId(skillId, threadId);
        }

        JsonNode tree = a2uiEngine.getTree(skillId);
        System.out.println("[activate] skillId=" + skillId + " tree=" + (tree != null ? tree.getClass().getSimpleName() : "null"));
        if (tree != null && tree.isObject()) {
            var obj = (com.fasterxml.jackson.databind.node.ObjectNode) tree;
            java.util.List<String> keys = new java.util.ArrayList<>();
            var iter = obj.fieldNames();
            while (iter.hasNext()) keys.add(iter.next());
            System.out.println("[activate] tree fieldNames=" + keys);
            if (tree.has("components")) {
                JsonNode comps = tree.get("components");
                System.out.println("[activate] components type=" + comps.getNodeType() + " size=" + comps.size());
            }
        }
        skills.put(skillId, skill.withStatus(SkillStatus.RUNNING));

        String runtime = skill.manifest().runtime();
        String handlersJs = null;
        Map<String, Object> capabilities = Map.of();
        if ("browser-js".equals(runtime) && skill.runtime() instanceof BrowserSkillRuntime bsr) {
            handlersJs = bsr.handlersJs();
            capabilities = skill.manifest().capabilities();
        }

        // Convert JsonNode tree to Map so Jackson serializes it correctly
        // (avoids potential JsonNode-in-Map serialization quirks)
        @SuppressWarnings("unchecked")
        Map<String, Object> uiMap = tree != null ? mapper.convertValue(tree, Map.class) : Map.of();
        System.out.println("[activate] uiMap keys=" + uiMap.keySet() + " hasComponents=" + uiMap.containsKey("components"));

        // Read sidebar.json for md runtime
        Map<String, Object> sidebarConfig = null;
        if ("md".equals(runtime)) {
            Path sidebarFile = Paths.get(skillsBaseDir, skillId, "genome", "sidebar.json");
            try {
                if (Files.exists(sidebarFile)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sc = mapper.readValue(sidebarFile.toFile(), Map.class);
                    sidebarConfig = sc;
                } else {
                    Path genomeDir = Paths.get(skillsBaseDir, skillId, "genome");
                    sidebarConfig = MdRuntime.buildDefaultSidebarConfig(genomeDir);
                }
            } catch (Exception e) {
                log.warn("Failed to read sidebar config for {}: {}", skillId, e.getMessage());
            }
        }

        eventBus.publish(new KernelEvent.SkillActivated(
            skillId, skill.manifest().name(), threadId, uiMap, List.of(), runtime, handlersJs, capabilities, sidebarConfig));

        // Write skill-event to conversation history
        writeConvEvent(threadId, skillId, "已激活技能 **" + skill.manifest().name() + "**", skillId);

        // Fire onInit asynchronously for kernel-js only.
        // Browser-js fires onInit on the frontend (no GraalJS).
        if (!"browser-js".equals(runtime)) {
            final String id = skillId;
            new Thread(() -> handleEvent(id, "__init__", Map.of(), INIT_TIMEOUT_SECONDS),
                "init-" + id).start();
        }

        log.info("Skill activated: {} (runtime={}, thread={})", skillId, runtime, threadId);
    }

    public void deactivate(String skillId) {
        deactivate(skillId, null);
    }

    public void deactivate(String skillId, String threadId) {
        LoadedSkill skill = skills.get(skillId);
        if (skill == null) return;

        if (threadId != null && !threadId.isEmpty()) {
            skillThreadMap.remove(skillId);
        }

        // Fire onDeactivate lifecycle hook
        if (skill.runtime() instanceof SkillRuntime sr) {
            sr.callLifecycleHandler("onDeactivate");
        }

        // Mark as stopped but keep runtime alive for re-activation
        skills.put(skillId, skill.withStatus(SkillStatus.STOPPED));

        // Notify frontend to clear workspace
        eventBus.publish(new KernelEvent.SkillDeactivated(skillId, threadId));
        writeConvEvent(threadId, skillId, "已停用技能 **" + skill.manifest().name() + "**", skillId);
        log.info("Skill deactivated: {} (thread={})", skillId, threadId);
    }

    // ── Event handling ──────────────────────────────────────────────

    public void handleEvent(String skillId, String eventId, Map<String, Object> inputs) {
        handleEvent(skillId, eventId, inputs, EVENT_TIMEOUT_SECONDS);
    }

    public void handleEvent(String skillId, String eventId, Map<String, Object> inputs, int timeoutSeconds) {
        System.out.println("[SkillManager] handleEvent: skillId=" + skillId + " eventId=" + eventId + " inputs=" + inputs);
        LoadedSkill skill = skills.get(skillId);
        if (skill == null) {
            System.out.println("[SkillManager] skill not found: " + skillId);
            eventBus.publish(new KernelEvent.KernelError(skillId, "SKILL_NOT_FOUND",
                "Skill not loaded: " + skillId));
            return;
        }
        if (skill.status() == SkillStatus.DRAINING) {
            log.info("Rejecting event {} for {} — skill is DRAINING", eventId, skillId);
            return;
        }

        // Browser-js skills handle events client-side — acknowledge and return
        if ("browser-js".equals(skill.manifest().runtime())) {
            eventBus.publish(new KernelEvent.EventAck(skillId, eventId, "ok", 0L, null));
            return;
        }

        SkillRuntime runtime = (SkillRuntime) skill.runtime();
        long start = System.currentTimeMillis();

        try {
            Map<String, Object> result = runtime.emitWithTimeout(eventId, inputs, timeoutSeconds);
            long latency = System.currentTimeMillis() - start;

            @SuppressWarnings("unchecked")
            List<Object> patches = (List<Object>) result.getOrDefault("patches", List.of());

            System.out.println("[SkillManager] emit result patches=" + patches);
            List<Object> appliedPatches = patches.isEmpty() ? List.of() : a2uiEngine.applyPatches(skillId, patches);
            if (!patches.isEmpty()) {
                System.out.println("[SkillManager] applied patches=" + appliedPatches);
                String tid = skillThreadMap.get(skillId);
                eventBus.publish(new KernelEvent.A2uiPatch(skillId, tid, appliedPatches));
            }
            eventBus.publish(new KernelEvent.EventAck(skillId, eventId, "ok", latency, appliedPatches));

            // W9: Async snapshot save after successful event (skip __init__)
            if (!"__init__".equals(eventId)) {
                LoadedSkill snapSkill = skills.get(skillId);
                if (snapSkill != null) {
                    new Thread(() -> {
                        try {
                            JsonNode tree = a2uiEngine.getTree(skillId);
                            if (tree != null) {
                                snapshotService.save(skillId, snapSkill.manifest().version(),
                                    snapSkill.manifest().abi(), tree, "event");
                            }
                        } catch (Exception ignored) { /* best-effort */ }
                    }, "snap-" + skillId).start();
                }
            }

        } catch (TimeoutException e) {
            long latency = System.currentTimeMillis() - start;
            eventBus.publish(new KernelEvent.EventAck(skillId, eventId, "timeout", latency, null));
            eventBus.publish(new KernelEvent.GuardianWarning("timeout", skillId,
                "Event " + eventId + " timed out after " + EVENT_TIMEOUT_SECONDS + "s"));

        } catch (ExecutionException e) {
            long latency = System.currentTimeMillis() - start;
            Throwable cause = e.getCause();
            String message = cause != null ? cause.getMessage() : e.getMessage();
            eventBus.publish(new KernelEvent.EventAck(skillId, eventId, "error", latency, null));
            eventBus.publish(new KernelEvent.KernelError(skillId, "HANDLER_ERROR", message));

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            eventBus.publish(new KernelEvent.EventAck(skillId, eventId, "error", latency, null));
            eventBus.publish(new KernelEvent.KernelError(skillId, "RUNTIME_ERROR", e.getMessage()));
        }
    }

    // ── Handoff ───────────────────────────────────────────────────────

    /**
     * Transfer control from one skill to another within the same thread.
     * Deactivates fromSkill, activates toSkill, and publishes a Handoff event.
     */
    public void handoff(String fromSkillId, String toSkillId, String threadId,
                        Map<String, Object> context) {
        LoadedSkill fromSkill = skills.get(fromSkillId);
        LoadedSkill toSkill = skills.get(toSkillId);
        if (fromSkill == null || toSkill == null) {
            log.warn("Handoff failed: from={} to={} — one or both not loaded", fromSkillId, toSkillId);
            eventBus.publish(new KernelEvent.KernelError(fromSkillId, "HANDOFF_FAILED",
                "Handoff 失败: skill 未加载"));
            return;
        }

        log.info("Handoff: {} -> {} (thread={})", fromSkillId, toSkillId, threadId);

        // Deactivate the source skill
        deactivate(fromSkillId, threadId);

        // Publish handoff event (before activating target, so frontend can prepare)
        eventBus.publish(new KernelEvent.SkillHandoff(fromSkillId, toSkillId, threadId, context));
        writeConvEvent(threadId, toSkillId,
            "**" + fromSkill.manifest().name() + "** → **" + toSkill.manifest().name() + "**", toSkillId);

        // Activate the target skill with context
        activate(toSkillId, threadId);

        // Fire __init__ with handoff context for kernel-js skills
        if (!"browser-js".equals(toSkill.manifest().runtime())) {
            final String tid = toSkillId;
            Map<String, Object> initInputs = new java.util.LinkedHashMap<>();
            initInputs.put("__handoff__", true);
            initInputs.put("__handoff_context__", context);
            initInputs.put("__handoff_from__", fromSkillId);
            new Thread(() -> handleEvent(tid, "__init__", initInputs, INIT_TIMEOUT_SECONDS),
                "handoff-init-" + tid).start();
        }
    }

    // ── Uninstall ───────────────────────────────────────────────────

    public void uninstall(String skillId) {
        LoadedSkill skill = skills.get(skillId);
        if (skill == null) {
            log.warn("Uninstall requested for unknown skill: {}", skillId);
            return;
        }

        // W9: Set DRAINING, wait for in-flight events
        skills.put(skillId, skill.withStatus(SkillStatus.DRAINING));
        log.info("Skill {} entering DRAINING", skillId);

        Object runtimeObj = skill.runtime();
        if (runtimeObj instanceof SkillRuntime runtime) {
            long deadline = System.currentTimeMillis() + 3000;
            while (runtime.hasInflightEvents() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
            if (runtime.hasInflightEvents()) {
                log.warn("Skill {} has in-flight events after DRAINING timeout, force-uninstalling", skillId);
            }
        }

        skills.remove(skillId);
        capManager.cancelPendingGates(skillId);

        // Fire onUninstall lifecycle hook (before runtime is closed)
        if (runtimeObj instanceof SkillRuntime sr) {
            sr.callLifecycleHandler("onUninstall");
        }

        try {
            if (runtimeObj instanceof SkillRuntime sr) sr.close();
            else if (runtimeObj instanceof BrowserSkillRuntime bsr) bsr.close();
        } catch (Exception e) {
            log.warn("Error closing runtime for {}: {}", skillId, e.getMessage());
        }

        a2uiEngine.unregister(skillId);
        if (skillIndexer != null) skillIndexer.remove(skillId);
        stateStore.removeSkill(skillId);
        ecosystemGuardian.forget(skillId);
        storeService.removeSkill(skillId);

        // W9: Delete skill directory from disk
        try {
            Path skillDir = Paths.get(skillsBaseDir, skillId);
            if (Files.isDirectory(skillDir)) {
                try (var files = Files.walk(skillDir)) {
                    files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try { Files.delete(p); } catch (Exception ignored) {}
                    });
                }
                log.info("Deleted skill directory: {}", skillDir);
            }
        } catch (Exception e) {
            log.warn("Failed to delete skill dir for {}: {}", skillId, e.getMessage());
        }

        String tid = skillThreadMap.remove(skillId);
        eventBus.publish(new KernelEvent.SkillDeactivated(skillId, tid));
        log.info("Skill uninstalled: {}", skillId);
    }

    // ── Shutdown ────────────────────────────────────────────────────

    /** W9: Graceful shutdown — save snapshots and close all runtimes. */
    public void shutdown() {
        log.info("SkillManager shutdown: {} skills to drain", skills.size());
        skills.forEach((skillId, skill) -> {
            try {
                JsonNode tree = a2uiEngine.getTree(skillId);
                if (tree != null) {
                    snapshotService.save(skillId, skill.manifest().version(),
                        skill.manifest().abi(), tree, "shutdown");
                }
            } catch (Exception e) {
                log.warn("Failed to save shutdown snapshot for {}", skillId, e);
            }
            try {
                Object rt = skill.runtime();
                if (rt instanceof SkillRuntime sr) sr.close();
                else if (rt instanceof BrowserSkillRuntime bsr) bsr.close();
            } catch (Exception e) {
                log.warn("Failed to close runtime for {}", skillId, e);
            }
        });
        skills.clear();
        log.info("SkillManager shutdown complete");
    }

    // ── Conversation integration ─────────────────────────────────────

    private String timeStr() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private void writeConvEvent(String threadId, String skillId, String content, String referenceId) {
        if (threadId == null || threadId.isEmpty()) return;
        conversationService.addMessageWithId(threadId, "skill-event", content, timeStr(),
            Map.of("referenceId", referenceId));
        if (onConvUpdate != null) {
            onConvUpdate.accept(threadId);
        }
    }

    // ── Multi-file helpers ────────────────────────────────────────────

    /**
     * Walk a handlers/ directory and return all .js files as a map of
     * relative-path → source. Keys are relative to the handlers dir
     * (e.g. "index.js", "lib/helpers.js", "features/crud.js").
     */
    private Map<String, String> walkJsFiles(Path handlersDir) throws Exception {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        try (var stream = Files.walk(handlersDir)) {
            stream
                .filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().endsWith(".js"))
                .forEach(f -> {
                    try {
                        String rel = handlersDir.relativize(f).toString()
                            .replace('\\', '/');
                        String source = Files.readString(f);
                        files.put(rel, source);
                    } catch (Exception ignored) {}
                });
        }
        return files;
    }

    // ── Queries ──────────────────────────────────────────────────────

    public LoadedSkill get(String skillId) {
        return skills.get(skillId);
    }

    public List<KernelEvent.SkillMeta> listSkills() {
        List<KernelEvent.SkillMeta> result = new ArrayList<>();
        skills.forEach((id, skill) -> {
            SkillManifest m = skill.manifest();
            String name = m.meta() != null
                ? String.valueOf(m.meta().getOrDefault("name", id))
                : id;
            Map<String, Object> caps = m.capabilities() != null
                ? m.capabilities()
                : Map.of();
            result.add(new KernelEvent.SkillMeta(
                id, name, skill.status().name().toLowerCase(), m.version(), caps));
        });
        return result;
    }

    public int loadedCount() {
        return skills.size();
    }
    public int getLoadedCount() {
        return skills.size();
    }
    public int getActiveCount() {
        return (int) skills.values().stream().filter(s -> s.status() == SkillStatus.RUNNING).count();
    }
    /** Returns per-skill capability details */
    public java.util.List<Map<String, Object>> getSkillCapabilities() {
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (var entry : skills.entrySet()) {
            var s = entry.getValue();
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("skillId", entry.getKey());
            item.put("name", s.manifest().meta() != null ? s.manifest().meta().getOrDefault("title", entry.getKey()) : entry.getKey());
            item.put("capabilities", new java.util.ArrayList<>(s.manifest().capabilities() != null ? s.manifest().capabilities().keySet() : java.util.Set.of()));
            list.add(item);
        }
        return list;
    }

    /** Returns capability distribution across loaded skills */
    public Map<String, Object> getCapabilityStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        Map<String, Integer> capCounts = new java.util.LinkedHashMap<>();
        java.util.List<String> known = java.util.List.of("database", "ui", "crypto", "websearch", "network", "file", "storage", "ai", "os");
        for (String k : known) capCounts.put(k, 0);
        int total = 0;
        for (LoadedSkill s : skills.values()) {
            total++;
            for (String k : known) {
                if (s.manifest().hasCapability(k)) capCounts.merge(k, 1, Integer::sum);
            }
        }
        stats.put("total", total);
        stats.put("byCapability", capCounts);
        return stats;
    }
}
