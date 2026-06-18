package com.wuwei.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.bus.event.KernelEvent;
import com.wuwei.capability.CapabilityManager;
import com.wuwei.llm.AgentFactory;
import com.wuwei.log.LogConfig;
import com.wuwei.llm.SkillGenerator;
import com.wuwei.skill.SkillManager;
import com.wuwei.store.ConversationService;
import com.wuwei.store.StoreService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

@Component
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final ObjectMapper mapper;
    private final EventBus eventBus;
    private final SkillManager skillManager;
    private final CapabilityManager capManager;
    private final SkillGenerator skillGenerator;
    private final AgentFactory agentFactory;
    private final StoreService storeService;
    private final ConversationService conversationService;
    private final long startTime = System.currentTimeMillis();

    public MessageRouter(ObjectMapper mapper, EventBus eventBus,
                         SkillManager skillManager, CapabilityManager capManager,
                         SkillGenerator skillGenerator, AgentFactory agentFactory,
                         StoreService storeService, ConversationService conversationService) {
        this.mapper = mapper;
        this.eventBus = eventBus;
        this.skillManager = skillManager;
        this.capManager = capManager;
        this.skillGenerator = skillGenerator;
        this.agentFactory = agentFactory;
        this.storeService = storeService;
        this.conversationService = conversationService;
    }

    public EventBus eventBus() {
        return eventBus;
    }

    public void route(WebSocketSession session, String payload) {
        System.out.println("[Router] route type starting, payload=" + payload.substring(0, Math.min(200, payload.length())));
        try {
            JsonNode msg = mapper.readTree(payload);
            String type = msg.has("type") ? msg.get("type").asText() : null;
            if (type == null) {
                sendError(session, "system", "MISSING_TYPE", "Message missing 'type' field");
                return;
            }
            System.out.println("[Router] dispatching type=" + type);

            switch (type) {
                case "user-intent"      -> handleUserIntent(session, msg);
                case "handle-event"     -> handleEvent(session, msg);
                case "install-skill"    -> handleInstallSkill(session, msg);
                case "uninstall-skill"  -> handleUninstallSkill(session, msg);
                case "activate-skill"   -> handleActivateSkill(session, msg);
                case "deactivate-skill" -> handleDeactivateSkill(session, msg);
                case "list-skills"      -> handleListSkills(session);
                case "confirm-gate"     -> handleConfirmGate(session, msg);
                case "set-rate-limit"   -> handleSetRateLimit(session, msg);
                case "get-rate-limit"   -> handleGetRateLimit(session);
                case "refine-skill"     -> handleRefineSkill(session, msg);
                case "get-skill-source" -> handleGetSkillSource(session, msg);
                case "get-metrics"      -> handleGetMetrics(session);
                case "list-logs"      -> handleListLogs(session, msg);
                case "get-log"        -> handleGetLog(session, msg);
                case "render-log"     -> handleRenderLog(session, msg);
                case "revoke-cap"         -> handleRevokeCap(session, msg);
                case "capability-proxy" -> handleCapabilityProxy(session, msg);
                case "set-model-routing" -> handleSetModelRouting(session, msg);
                case "list-model-routing" -> handleListModelRouting(session);
                case "delete-model-routing" -> handleDeleteModelRouting(session, msg);
                case "create-conversation" -> handleCreateConversation(session, msg);
                case "list-conversations" -> handleListConversations(session, msg);
                case "get-conversation" -> handleGetConversation(session, msg);
                case "delete-conversation" -> handleDeleteConversation(session, msg);
                case "get-home-conversation" -> handleGetHomeConversation(session, msg);
                case "find-or-create-conversation" -> handleFindOrCreateConversation(session, msg);
                case "set-thread-active-skill" -> handleSetThreadActiveSkill(session, msg);
                case "get-thread" -> handleGetThread(session, msg);
                case "update-conversation-title" -> handleUpdateConversationTitle(session, msg);
                case "skill-handoff" -> handleSkillHandoff(session, msg);
                case "delete-message" -> handleDeleteMessage(session, msg);
                default -> sendError(session, "system", "UNKNOWN_TYPE", "Unknown message type: " + type);
            }
        } catch (Exception e) {
            log.error("Message routing error", e);
            sendError(session, "system", "ROUTER_ERROR", e.getMessage());
        }
    }

    private void handleUserIntent(WebSocketSession session, JsonNode msg) {
        String text = msg.has("payload") ? msg.get("payload").get("text").asText() : "";
        String threadId = extractNullableText(msg, "threadId");
        if (text.isBlank()) {
            eventBus.publish(new KernelEvent.PlanStep("error", "意图描述不能为空", threadId));
            return;
        }
        log.info("user-intent: {} threadId={}", text, threadId);

        // Auto-title: extract first ~30 chars of first user message as title
        Map<String, Object> thread = conversationService.getConversation(threadId);
        if (thread != null && "新对话".equals(thread.get("title"))) {
            String autoTitle = text.length() > 30 ? text.substring(0, 30) + "..." : text;
            conversationService.updateTitle(threadId, autoTitle);
        }

        if (!skillGenerator.enabled()) {
            eventBus.publish(new KernelEvent.PlanStep("error",
                "LLM 未配置。请设置 OPENAI_API_KEY 环境变量", threadId));
            return;
        }

        String now = timeStr();
        if (threadId == null || threadId.isEmpty()) {
            eventBus.publish(new KernelEvent.PlanStep("error", "threadId 不能为空", null));
            return;
        }

        // Write user message to DB, capture its ID
        String userMsgId = conversationService.addMessageWithId(threadId, "user", text, now, null);
        String genMsgId = "gen-" + userMsgId;

        // Push user message to frontend
        pushMessageUpdate(threadId, buildMessageRecord(userMsgId, "user", text, now, null));

        // Push initial generation card (all steps pending)
        List<Map<String, Object>> initialSteps = List.of(
            stepMap("generating", "生成技能代码", "pending"),
            stepMap("normalizing", "规范化输出", "pending"),
            stepMap("auditing", "安全审计", "pending"),
            stepMap("repairing", "自动修复", "pending"),
            stepMap("installing", "安装部署", "pending")
        );
        Map<String, Object> genMeta = new LinkedHashMap<>();
        genMeta.put("type", "generation");
        genMeta.put("steps", initialSteps);
        genMeta.put("skillId", null);
        genMeta.put("allDone", false);
        pushMessageUpdate(threadId, buildMessageRecord(genMsgId, "assistant", "", now, genMeta));

        Map<String, String> modelOverride = extractModelOverride(msg);

        final String tid = threadId;
        final String gMsgId = genMsgId;
        new Thread(() -> {
            try {
                System.out.println("[kernel] [llm-generate] thread started, calling skillGenerator.generate()");
                String resultId = skillGenerator.generate(text, modelOverride, tid, gMsgId);
                System.out.println("[kernel] [llm-generate] resultId=" + (resultId != null ? resultId : "FAILED"));
                log.info("Generation result: {}", resultId != null ? resultId : "FAILED");
            } catch (Exception e) {
                System.out.println("[kernel] [llm-generate] EXCEPTION: " + e.getClass().getName() + ": " + e.getMessage());
                log.error("Generation thread failed", e);
                eventBus.publish(new KernelEvent.PlanStep("error", "生成线程异常: " + e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : ""), tid));
                // Update generation card to error so UI doesn't stay stuck
                List<Map<String, Object>> errorSteps = List.of(
                    stepMap("generating", "生成技能代码", "error"),
                    stepMap("normalizing", "规范化输出", "pending"),
                    stepMap("auditing", "安全审计", "pending"),
                    stepMap("repairing", "自动修复", "pending"),
                    stepMap("installing", "安装部署", "pending")
                );
                Map<String, Object> errorMeta = new LinkedHashMap<>();
                errorMeta.put("type", "generation");
                errorMeta.put("steps", errorSteps);
                errorMeta.put("skillId", null);
                errorMeta.put("allDone", false);
                pushMessageUpdate(tid, buildMessageRecord(gMsgId, "assistant", "", timeStr(), errorMeta));
            }
        }, "llm-generate").start();
    }

    private void handleRefineSkill(WebSocketSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        String feedback = msg.has("payload") ? msg.get("payload").get("feedback").asText() : "";
        String threadId = extractNullableText(msg, "threadId");
        if (skillId.equals("unknown") || feedback.isBlank()) {
            eventBus.publish(new KernelEvent.PlanStep("error", "优化请求缺少 skillId 或 feedback", threadId));
            return;
        }
        log.info("refine-skill: skill={} feedback={} threadId={}", skillId, feedback, threadId);

        if (!skillGenerator.enabled()) {
            eventBus.publish(new KernelEvent.PlanStep("error",
                "LLM 未配置。请设置 OPENAI_API_KEY 环境变量", threadId));
            return;
        }

        Map<String, String> modelOverride = extractModelOverride(msg);

        // Write user feedback message to DB
        String now = timeStr();
        if (threadId != null && !threadId.isEmpty()) {
            String userMsgId = conversationService.addMessageWithId(threadId, "user", feedback, now, null);
            String genMsgId = "gen-" + userMsgId;

            // Push user message
            pushMessageUpdate(threadId, buildMessageRecord(userMsgId, "user", feedback, now, null));

            // Push initial generation card
            List<Map<String, Object>> initialSteps = List.of(
                stepMap("generating", "优化技能代码", "pending"),
                stepMap("normalizing", "规范化输出", "pending"),
                stepMap("auditing", "安全审计", "pending"),
                stepMap("repairing", "自动修复", "pending"),
                stepMap("installing", "安装部署", "pending")
            );
            Map<String, Object> genMeta = new LinkedHashMap<>();
            genMeta.put("type", "generation");
            genMeta.put("steps", initialSteps);
            genMeta.put("skillId", null);
            genMeta.put("allDone", false);
            pushMessageUpdate(threadId, buildMessageRecord(genMsgId, "assistant", "", now, genMeta));

            final String tid = threadId;
            final String gMsgId = genMsgId;
            new Thread(() -> {
                try {
                    String resultId = skillGenerator.refine(skillId, feedback, modelOverride, tid, gMsgId);
                    log.info("Refine result: {}", resultId != null ? resultId : "FAILED");
                } catch (Exception e) {
                    log.error("Refine thread failed", e);
                    eventBus.publish(new KernelEvent.PlanStep("error", "优化线程异常: " + e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : ""), tid));
                    List<Map<String, Object>> errorSteps = List.of(
                        stepMap("generating", "优化技能代码", "error"),
                        stepMap("normalizing", "规范化输出", "pending"),
                        stepMap("auditing", "安全审计", "pending"),
                        stepMap("repairing", "自动修复", "pending"),
                        stepMap("installing", "安装部署", "pending")
                    );
                    Map<String, Object> errorMeta = new LinkedHashMap<>();
                    errorMeta.put("type", "generation");
                    errorMeta.put("steps", errorSteps);
                    errorMeta.put("skillId", null);
                    errorMeta.put("allDone", false);
                    pushMessageUpdate(tid, buildMessageRecord(gMsgId, "assistant", "", timeStr(), errorMeta));
                }
            }, "llm-refine").start();
        }
    }

    private void handleEvent(WebSocketSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        String eventId = extractText(msg, "eventId");
        Map<String, Object> inputs = extractMap(msg, "inputs");
        System.out.println("[Router] handle-event: skill=" + skillId + " event=" + eventId + " inputs=" + inputs);
        skillManager.handleEvent(skillId, eventId, inputs);
    }

    private void handleInstallSkill(WebSocketSession session, JsonNode msg) {
        String sourcePath = extractText(msg, "path");
        if (sourcePath.equals("unknown")) {
            eventBus.publishTo(session, new KernelEvent.KernelError("system", "MISSING_PATH",
                "install-skill requires a 'path' field with the local folder path"));
            return;
        }

        Path srcDir = Paths.get(sourcePath);
        if (!Files.isDirectory(srcDir) || !Files.exists(srcDir.resolve("skill.json"))) {
            eventBus.publishTo(session, new KernelEvent.KernelError("system", "INVALID_SKILL_DIR",
                "Not a valid skill directory (missing skill.json): " + sourcePath));
            return;
        }

        String skillId;
        try {
            JsonNode manifest = mapper.readTree(srcDir.resolve("skill.json").toFile());
            skillId = manifest.has("id") ? manifest.get("id").asText() : srcDir.getFileName().toString();
        } catch (Exception e) {
            eventBus.publishTo(session, new KernelEvent.KernelError("system", "INVALID_MANIFEST",
                "Failed to read skill.json: " + e.getMessage()));
            return;
        }

        // Copy to ~/.wuwei/skills/
        String home = System.getProperty("user.home");
        Path destDir = Paths.get(home, ".wuwei", "skills", skillId);
        try {
            // Normalize both paths to detect when src == dest (re-install of same path).
            // Without this check, the delete+copy below would wipe the source directory.
            Path srcAbs = srcDir.toRealPath();
            boolean sameDir = Files.exists(destDir) && srcAbs.equals(destDir.toRealPath());

            if (!sameDir) {
                if (Files.exists(destDir)) {
                    // Remove old version first
                    try (Stream<Path> files = Files.walk(destDir)) {
                        files.sorted(Comparator.reverseOrder()).forEach(p -> {
                            try { Files.delete(p); } catch (Exception ignored) {}
                        });
                    }
                }
                Files.createDirectories(destDir);
                try (Stream<Path> files = Files.walk(srcDir)) {
                    files.forEach(src -> {
                        try {
                            Path rel = srcDir.relativize(src);
                            Path dest = destDir.resolve(rel);
                            if (Files.isDirectory(src)) {
                                Files.createDirectories(dest);
                            } else {
                                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (Exception ignored) {}
                    });
                }
            } else {
                log.info("Install source equals dest ({}), skipping copy", sourcePath);
            }

            log.info("Installing skill {} from {}", skillId, sourcePath);
            eventBus.publish(new KernelEvent.SkillLoading(skillId));

            skillManager.loadFromDirectory(destDir);
            skillManager.activate(skillId);

            eventBus.publishTo(session, new KernelEvent.SystemNotify(
                "安装成功", "Skill " + skillId + " 已安装并激活"));
        } catch (Exception e) {
            log.error("Failed to install skill {}: {}", skillId, e.getMessage());
            eventBus.publishTo(session, new KernelEvent.KernelError(skillId, "INSTALL_FAILED",
                "安装失败: " + e.getMessage()));
        }
    }

    private void handleUninstallSkill(WebSocketSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        log.info("uninstall-skill: {}", skillId);
        capManager.cancelPendingGates(skillId);
        skillManager.uninstall(skillId);
    }

    private void handleActivateSkill(WebSocketSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        String threadId = extractNullableText(msg, "threadId");
        log.info("activate-skill: {} threadId={}", skillId, threadId);
        skillManager.activate(skillId, threadId);
    }

    private void handleDeactivateSkill(WebSocketSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        String threadId = extractNullableText(msg, "threadId");
        log.info("deactivate-skill: {} threadId={}", skillId, threadId);
        skillManager.deactivate(skillId, threadId);
    }

    private void handleListSkills(WebSocketSession session) {
        List<KernelEvent.SkillMeta> skills = skillManager.listSkills();
        eventBus.publish(new KernelEvent.SkillList(skills));
    }

    private void handleConfirmGate(WebSocketSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        String capName = extractText(msg, "capName");
        boolean approved = msg.has("approved") && msg.get("approved").asBoolean();
        log.info("confirm-gate: skill={} cap={} approved={}", skillId, capName, approved);
        capManager.resolveGate(skillId, capName, approved);
    }

    private void handleSetRateLimit(WebSocketSession session, JsonNode msg) {
        boolean enabled = msg.has("enabled") && msg.get("enabled").asBoolean();
        log.info("set-rate-limit: {}", enabled);
        eventBus.setRateLimitEnabled(enabled);
        eventBus.publishTo(session, new KernelEvent.SystemNotify(
            "事件限流", enabled ? "事件限流已开启" : "事件限流已关闭"));
    }

    private void handleGetRateLimit(WebSocketSession session) {
        boolean enabled = eventBus.isRateLimitEnabled();
        eventBus.publishTo(session, new KernelEvent.SystemNotify(
            "事件限流状态", enabled ? "当前限流状态：开启" : "当前限流状态：关闭"));
    }

    private void handleRevokeCap(WebSocketSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        String capName = extractText(msg, "capName");
        log.info("revoke-cap: skill={} cap={}", skillId, capName);
        capManager.revoke(skillId, capName);
    }

    private void handleCapabilityProxy(WebSocketSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        String capName = extractText(msg, "capName");
        String method = extractText(msg, "method");
        String requestId = extractText(msg, "requestId");
        List<Object> args = extractList(msg, "args");

        log.info("capability-proxy: skill={} cap={}.{}({})", skillId, capName, method, args);

        Object result = capManager.executeProxy(skillId, capName, method, args);

        eventBus.publishTo(session, new KernelEvent.CapabilityProxyResult(
            skillId, requestId, result, null));
    }

    // ── Workbench: view skill source ────────────────────────────

    private void handleGetSkillSource(WebSocketSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        String home = System.getProperty("user.home");
        Path skillDir = Paths.get(home, ".wuwei", "skills", skillId);

        if (!Files.isDirectory(skillDir)) {
            eventBus.publishTo(session, new KernelEvent.KernelError(skillId, "SKILL_NOT_FOUND",
                "Skill directory not found"));
            return;
        }

        try {
            // Walk all files in skill directory recursively
            List<Map<String, Object>> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(skillDir)) {
                walk.filter(Files::isRegularFile).forEach(f -> {
                    Map<String, Object> fi = new LinkedHashMap<>();
                    String relPath = skillDir.relativize(f).toString().replace('\\', '/');
                    fi.put("path", relPath);
                    try {
                        fi.put("size", Files.size(f));
                    } catch (Exception ignored) { fi.put("size", 0); }
                    // Read content for text files; skip binary/large files
                    String name = f.getFileName().toString().toLowerCase();
                    boolean isText = name.endsWith(".js") || name.endsWith(".json")
                        || name.endsWith(".md") || name.endsWith(".css")
                        || name.endsWith(".html") || name.endsWith(".txt")
                        || name.endsWith(".xml") || name.endsWith(".yaml")
                        || name.endsWith(".yml") || name.endsWith(".toml");
                    if (isText) {
                        try {
                            fi.put("content", Files.readString(f));
                        } catch (Exception ignored) { fi.put("content", ""); }
                    }
                    files.add(fi);
                });
            }
            files.sort((a, b) -> ((String) a.get("path")).compareToIgnoreCase((String) b.get("path")));

            String json = mapper.writeValueAsString(Map.of(
                "type", "skill-source",
                "skillId", skillId,
                "files", files
            ));
            eventBus.publishTo(session, new KernelEvent.SystemNotify(
                "源代码: " + skillId,
                "共 " + files.size() + " 个文件"));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.error("Failed to read skill source {}: {}", skillId, e.getMessage());
            eventBus.publishTo(session, new KernelEvent.KernelError(skillId, "SOURCE_READ_ERROR",
                "无法读取源代码: " + e.getMessage()));
        }
    }

    // ── Metrics ──────────────────────────────────────────────────

    private void handleGetMetrics(WebSocketSession session) {
        try {
            Runtime rt = Runtime.getRuntime();
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
            Path skillsDir = Paths.get(System.getProperty("user.home"), ".wuwei", "skills");

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("type", "kernel-metrics");
            metrics.put("uptimeMs", System.currentTimeMillis() - startTime);
            metrics.put("startTime", runtimeBean.getStartTime());

            // GraalVM image info
            Map<String, Object> graal = new LinkedHashMap<>();
            graal.put("vendor", System.getProperty("java.vendor", ""));
            graal.put("vmName", System.getProperty("java.vm.name", ""));
            graal.put("vmVersion", System.getProperty("java.vm.version", ""));
            graal.put("imageKind", System.getProperty("org.graalvm.nativeimage.kind", "unknown"));
            graal.put("imageCode", System.getProperty("org.graalvm.nativeimage.imagecode", "unknown"));
            graal.put("inputArgs", runtimeBean.getInputArguments());
            metrics.put("graalvm", graal);

            // Process
            Map<String, Object> proc = new LinkedHashMap<>();
            ProcessHandle ph = ProcessHandle.current();
            proc.put("pid", ph.pid());
            proc.put("command", ph.info().command().orElse(""));
            proc.put("totalMemoryBytes", rt.totalMemory());
            proc.put("freeMemoryBytes", rt.freeMemory());
            proc.put("maxMemoryBytes", rt.maxMemory());
            metrics.put("process", proc);

            // System
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("availableProcessors", rt.availableProcessors());
            sys.put("osName", os.getName() + " " + os.getVersion());
            sys.put("osArch", os.getArch());
            sys.put("loadAvg", os.getSystemLoadAverage());
            sys.put("totalPhysicalMB", ((com.sun.management.OperatingSystemMXBean) os).getTotalPhysicalMemorySize() / (1024 * 1024));
            sys.put("freePhysicalMB", ((com.sun.management.OperatingSystemMXBean) os).getFreePhysicalMemorySize() / (1024 * 1024));
            sys.put("committedVirtualMB", ((com.sun.management.OperatingSystemMXBean) os).getCommittedVirtualMemorySize() / (1024 * 1024));
            metrics.put("system", sys);

            // Memory
            metrics.put("heapUsedMB", mem.getHeapMemoryUsage().getUsed() / (1024 * 1024));
            metrics.put("heapCommittedMB", mem.getHeapMemoryUsage().getCommitted() / (1024 * 1024));
            metrics.put("heapMaxMB", mem.getHeapMemoryUsage().getMax() / (1024 * 1024));
            metrics.put("heapInitMB", mem.getHeapMemoryUsage().getInit() / (1024 * 1024));
            metrics.put("nonHeapUsedMB", mem.getNonHeapMemoryUsage().getUsed() / (1024 * 1024));

            // GC
            List<Map<String, Object>> gcList = new ArrayList<>();
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                Map<String, Object> gcInfo = new LinkedHashMap<>();
                gcInfo.put("name", gc.getName());
                gcInfo.put("collectionCount", gc.getCollectionCount());
                gcInfo.put("collectionTimeMs", gc.getCollectionTime());
                gcList.add(gcInfo);
            }
            metrics.put("garbageCollectors", gcList);

            // Threads
            metrics.put("threadCount", threadBean.getThreadCount());
            metrics.put("peakThreadCount", threadBean.getPeakThreadCount());
            metrics.put("daemonThreadCount", threadBean.getDaemonThreadCount());
            metrics.put("totalStartedThreadCount", threadBean.getTotalStartedThreadCount());

            // Skills
            metrics.put("activeSkills", skillManager.getActiveCount());
            metrics.put("loadedSkills", skillManager.getLoadedCount());
            metrics.put("capabilityStats", skillManager.getCapabilityStats());
            metrics.put("skillCapabilities", skillManager.getSkillCapabilities());
            metrics.put("wsSessions", eventBus.getSessionCount());
            try {
                long total = 0; int fileCount = 0;
                if (Files.isDirectory(skillsDir)) {
                    try (Stream<Path> walk = Files.walk(skillsDir)) {
                        var list = walk.filter(Files::isRegularFile).toList();
                        fileCount = list.size();
                        total = list.stream().mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0L; } }).sum();
                    }
                }
                metrics.put("skillFileCount", fileCount);
                metrics.put("skillDiskMB", total / (1024 * 1024));
            } catch (Exception ignored) {}

            // All related processes
            List<Map<String, Object>> procs = new ArrayList<>();
            ProcessHandle.allProcesses().forEach(p -> {
                String cmd = p.info().command().orElse("").toLowerCase();
                if (cmd.contains("wuwei") || cmd.contains("tauri") || cmd.contains("vite") || cmd.contains("node")) {
                    Map<String, Object> pi = new LinkedHashMap<>();
                    pi.put("pid", p.pid());
                    pi.put("command", p.info().command().orElse(""));
                    pi.put("args", String.join(" ", p.info().arguments().orElse(new String[0])));
                    try { pi.put("cpuMs", p.info().totalCpuDuration().orElse(java.time.Duration.ZERO).toMillis()); } catch (Exception e) { pi.put("cpuMs", 0); }
                    procs.add(pi);
                }
            });
            metrics.put("relatedProcesses", procs);

            session.sendMessage(new TextMessage(mapper.writeValueAsString(metrics)));
        } catch (Exception e) {
            sendError(session, "system", "METRICS_ERROR", e.getMessage());
        }
    }

    // ── Log viewer ────────────────────────────────────────────────

    private void handleListLogs(WebSocketSession session, JsonNode msg) {
        String source = msg.has("source") ? msg.get("source").asText() : "kernel";
        try {
            String json = mapper.writeValueAsString(Map.of(
                "type", "log-dates", "source", source,
                "dates", LogConfig.listDates(source)));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            sendError(session, "system", "LOG_ERROR", e.getMessage());
        }
    }

    private void handleGetLog(WebSocketSession session, JsonNode msg) {
        String source = msg.has("source") ? msg.get("source").asText() : "kernel";
        String date = msg.has("date") ? msg.get("date").asText() : LogConfig.today();
        try {
            String content = LogConfig.readLog(source, date);
            String json = mapper.writeValueAsString(Map.of(
                "type", "log-content", "source", source, "date", date, "content", content));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            sendError(session, "system", "LOG_ERROR", e.getMessage());
        }
    }

    private void handleRenderLog(WebSocketSession session, JsonNode msg) {
        String level = msg.has("level") ? msg.get("level").asText() : "info";
        String logMsg = msg.has("message") ? msg.get("message").asText() : "";
        LogConfig.logRender(level, logMsg);
    }

    // ── Model routing ─────────────────────────────────────────────

    private void handleSetModelRouting(WebSocketSession session, JsonNode msg) {
        String taskType = msg.has("taskType") ? msg.get("taskType").asText() : "";
        String provider = msg.has("provider") ? msg.get("provider").asText() : "";
        String model = msg.has("model") ? msg.get("model").asText() : "";
        String apiUrl = msg.has("apiUrl") ? msg.get("apiUrl").asText() : "";
        String apiKey = msg.has("apiKey") ? msg.get("apiKey").asText() : "";
        String params = msg.has("params") ? msg.get("params").asText() : "{}";

        if (taskType.isBlank() || provider.isBlank() || model.isBlank()) {
            eventBus.publishTo(session, new KernelEvent.KernelError("system", "INVALID_ROUTING",
                "set-model-routing requires taskType, provider, and model"));
            return;
        }

        log.info("set-model-routing: taskType={} -> {}/{}", taskType, provider, model);
        storeService.updateModelRouting(taskType, provider, model, apiUrl, apiKey, params);
        try {
            String json = mapper.writeValueAsString(Map.of(
                "type", "model-routing-updated",
                "taskType", taskType,
                "provider", provider,
                "model", model,
                "apiUrl", apiUrl,
                "apiKey", apiKey,
                "params", params
            ));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("Failed to send model-routing-updated: {}", e.getMessage());
        }
        eventBus.publishTo(session, new KernelEvent.SystemNotify(
            "模型路由已更新", taskType + " → " + provider + "/" + model));
    }

    private void handleListModelRouting(WebSocketSession session) {
        Map<String, Map<String, String>> entries = storeService.listModelRouting();
        try {
            String json = mapper.writeValueAsString(Map.of(
                "type", "model-routing-list",
                "entries", entries
            ));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("Failed to send model-routing-list: {}", e.getMessage());
        }
    }

    private void handleDeleteModelRouting(WebSocketSession session, JsonNode msg) {
        String taskType = msg.has("taskType") ? msg.get("taskType").asText() : "";
        if (taskType.isBlank()) {
            eventBus.publishTo(session, new KernelEvent.KernelError("system", "INVALID_ROUTING",
                "delete-model-routing requires taskType"));
            return;
        }
        log.info("delete-model-routing: taskType={}", taskType);
        storeService.deleteModelRouting(taskType);
        try {
            String json = mapper.writeValueAsString(Map.of(
                "type", "model-routing-deleted",
                "taskType", taskType
            ));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("Failed to send model-routing-deleted: {}", e.getMessage());
        }
    }

    // ── Conversation persistence ──────────────────────────────────

    private void handleCreateConversation(WebSocketSession session, JsonNode msg) {
        String skillId = msg.has("skillId") ? msg.get("skillId").asText() : null;
        String skillName = msg.has("skillName") ? msg.get("skillName").asText() : null;
        String correlationId = msg.has("correlationId") ? msg.get("correlationId").asText() : null;
        Map<String, Object> conv = conversationService.createConversation(
            "".equals(skillId) ? null : skillId,
            "".equals(skillName) ? null : skillName);
        sendJson(session, "conversation-created", Map.of("conversation", conv), correlationId);
    }

    private void handleListConversations(WebSocketSession session, JsonNode msg) {
        String correlationId = msg.has("correlationId") ? msg.get("correlationId").asText() : null;
        List<Map<String, Object>> convs = conversationService.listConversations();
        sendJson(session, "conversation-list", Map.of("conversations", convs), correlationId);
    }

    private void handleGetConversation(WebSocketSession session, JsonNode msg) {
        String convId = extractText(msg, "convId");
        String correlationId = msg.has("correlationId") ? msg.get("correlationId").asText() : null;
        Map<String, Object> conv = conversationService.getConversation(convId);
        if (conv == null) {
            sendError(session, "system", "CONV_NOT_FOUND", "Conversation not found: " + convId);
            return;
        }
        sendJson(session, "conversation-detail", Map.of("conversation", conv), correlationId);
    }

    private void handleDeleteConversation(WebSocketSession session, JsonNode msg) {
        String convId = extractText(msg, "convId");
        String correlationId = msg.has("correlationId") ? msg.get("correlationId").asText() : null;
        conversationService.deleteConversation(convId);
        sendJson(session, "conversation-deleted", Map.of("convId", convId), correlationId);
    }

    private void handleGetHomeConversation(WebSocketSession session, JsonNode msg) {
        String correlationId = msg.has("correlationId") ? msg.get("correlationId").asText() : null;
        Map<String, Object> conv = conversationService.getHomeConversation();
        sendJson(session, "home-conversation", Map.of("conversation", conv), correlationId);
    }

    private void handleFindOrCreateConversation(WebSocketSession session, JsonNode msg) {
        String skillId = msg.has("skillId") && !msg.get("skillId").asText().isEmpty()
            ? msg.get("skillId").asText() : null;
        String skillName = msg.has("skillName") ? msg.get("skillName").asText() : null;
        String correlationId = msg.has("correlationId") ? msg.get("correlationId").asText() : null;
        Map<String, Object> conv = conversationService.findOrCreateConversation(skillId, skillName);
        sendJson(session, "conversation-ready", Map.of("conversation", conv), correlationId);
    }

    private void handleSetThreadActiveSkill(WebSocketSession session, JsonNode msg) {
        String convId = extractText(msg, "convId");
        String skillId = msg.has("skillId") && !msg.get("skillId").asText().isEmpty()
            ? msg.get("skillId").asText() : null;
        String correlationId = msg.has("correlationId") ? msg.get("correlationId").asText() : null;
        conversationService.setActiveSkill(convId, skillId);
        sendJson(session, "thread-active-skill-set",
            Map.of("convId", convId, "skillId", skillId != null ? skillId : ""), correlationId);
    }

    private void handleUpdateConversationTitle(WebSocketSession session, JsonNode msg) {
        String convId = extractText(msg, "convId");
        String title = extractText(msg, "title");
        String correlationId = msg.has("correlationId") ? msg.get("correlationId").asText() : null;
        if (title == null || title.isBlank()) return;
        conversationService.updateTitle(convId, title.trim());
        sendJson(session, "conversation-title-updated", Map.of("convId", convId, "title", title.trim()), correlationId);
    }

    private void handleGetThread(WebSocketSession session, JsonNode msg) {
        String convId = extractText(msg, "convId");
        String correlationId = msg.has("correlationId") ? msg.get("correlationId").asText() : null;
        Map<String, Object> conv = conversationService.getConversation(convId);
        if (conv == null) {
            sendError(session, "system", "THREAD_NOT_FOUND", "Thread not found: " + convId);
            return;
        }
        sendJson(session, "thread-detail", Map.of("thread", conv), correlationId);
    }

    private void handleSkillHandoff(WebSocketSession session, JsonNode msg) {
        String fromSkillId = extractText(msg, "fromSkillId");
        String toSkillId = extractText(msg, "toSkillId");
        String threadId = extractNullableText(msg, "threadId");
        Map<String, Object> context = extractMap(msg, "context");
        log.info("skill-handoff: {} -> {} (thread={})", fromSkillId, toSkillId, threadId);
        skillManager.handoff(fromSkillId, toSkillId, threadId, context);
    }

    /** Push the full message list for a thread to all connected clients. */
    public void pushConversationUpdate(String threadId) {
        if (threadId == null || threadId.isEmpty()) return;
        List<Map<String, Object>> messages = conversationService.getMessages(threadId);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ns", "conv");
            payload.put("type", "conversation-update");
            payload.put("threadId", threadId);
            payload.put("messages", messages);
            String json = mapper.writeValueAsString(payload);
            eventBus.broadcastRaw(json);
        } catch (Exception e) {
            log.warn("Failed to push conversation-update: {}", e.getMessage());
        }
    }

    /** Push a single message insert/update to all connected clients and persist to DB. */
    public void pushMessageUpdate(String threadId, Map<String, Object> message) {
        if (threadId == null || threadId.isEmpty()) return;
        // Persist to DB
        String id = (String) message.get("id");
        String role = (String) message.get("role");
        String content = (String) message.getOrDefault("content", "");
        String time = (String) message.getOrDefault("time", "");
        Map<String, Object> meta = extractMeta(message);
        conversationService.upsertMessage(threadId, id, role, content, time, meta);

        // Broadcast to frontend
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ns", "conv");
            payload.put("type", "message-updated");
            payload.put("threadId", threadId);
            payload.put("message", message);
            String json = mapper.writeValueAsString(payload);
            eventBus.broadcastRaw(json);
        } catch (Exception e) {
            log.warn("Failed to push message-updated: {}", e.getMessage());
        }
    }

    /** Extract meta fields from a message record (everything except id/role/content/time). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMeta(Map<String, Object> message) {
        Map<String, Object> meta = new LinkedHashMap<>();
        for (var entry : message.entrySet()) {
            String key = entry.getKey();
            if (!key.equals("id") && !key.equals("role") && !key.equals("content") && !key.equals("time")) {
                meta.put(key, entry.getValue());
            }
        }
        return meta.isEmpty() ? null : meta;
    }

    /** Build a message record map for pushMessageUpdate. */
    public Map<String, Object> buildMessageRecord(String id, String role, String content,
                                                   String time, Map<String, Object> meta) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", id);
        rec.put("role", role);
        rec.put("content", content);
        rec.put("time", time);
        if (meta != null) {
            rec.putAll(meta);
        }
        return rec;
    }

    private Map<String, Object> stepMap(String key, String label, String status) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("key", key);
        s.put("label", label);
        s.put("status", status);
        return s;
    }

    private void handleDeleteMessage(WebSocketSession session, JsonNode msg) {
        String threadId = extractNullableText(msg, "threadId");
        String messageId = extractText(msg, "messageId");
        if (threadId == null || threadId.isEmpty() || messageId.equals("unknown")) {
            sendError(session, "system", "INVALID_REQUEST", "delete-message requires threadId and messageId");
            return;
        }
        log.info("delete-message: thread={} msg={}", threadId, messageId);
        conversationService.deleteMessage(threadId, messageId);
        pushConversationUpdate(threadId);
    }

    // ── helpers ──────────────────────────────────────────────────

    private String extractText(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : "unknown";
    }

    /** Like extractText but returns null when the JSON value is null or missing. */
    private String extractNullableText(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMap(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return Map.of();
        try {
            return mapper.convertValue(node.get(field), Map.class);
        } catch (Exception e) {
            log.warn("Failed to extract map field {}: {}", field, e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> extractList(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return List.of();
        try {
            return mapper.convertValue(node.get(field), List.class);
        } catch (Exception e) {
            log.warn("Failed to extract list field {}: {}", field, e.getMessage());
            return List.of();
        }
    }


    private void sendJson(WebSocketSession session, String type, Map<String, Object> data, String correlationId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(data);
            payload.put("ns", "conv");
            payload.put("type", type);
            if (correlationId != null) {
                payload.put("correlationId", correlationId);
            }
            session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.warn("Failed to send {}: {}", type, e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, String skillId, String code, String message) {
        eventBus.publish(new KernelEvent.KernelError(skillId, code, message));
    }

    private String timeStr() {
        return java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** Extract optional model override from the WebSocket payload. */
    private Map<String, String> extractModelOverride(JsonNode msg) {
        if (!msg.has("payload")) return null;
        JsonNode p = msg.get("payload");
        Map<String, String> override = new java.util.LinkedHashMap<>();
        if (p.has("model") && !p.get("model").asText().isBlank())
            override.put("model", p.get("model").asText());
        if (p.has("provider") && !p.get("provider").asText().isBlank())
            override.put("provider", p.get("provider").asText());
        if (p.has("apiKey") && !p.get("apiKey").asText().isBlank())
            override.put("apiKey", p.get("apiKey").asText());
        if (p.has("apiUrl") && !p.get("apiUrl").asText().isBlank())
            override.put("apiUrl", p.get("apiUrl").asText());
        return override.isEmpty() ? null : override;
    }
}
