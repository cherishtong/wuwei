package com.wuwei.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.bus.EventBus;
import com.wuwei.bus.event.KernelEvent;
import com.wuwei.gate.AstAuditor;
import com.wuwei.gate.EcosystemGuardian;
import com.wuwei.gate.GateException;
import com.wuwei.rag.SkillIndexer;
import com.wuwei.skill.SkillGenome;
import com.wuwei.skill.SkillManager;
import com.wuwei.skill.SkillManifest;
import com.wuwei.snapshot.SnapshotService;
import com.wuwei.store.ConversationService;
import com.wuwei.store.SkillMemoryService;
import com.wuwei.store.StoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Skill generation pipeline via Spring AI ChatClient.
 *
 * Flow: intent → ChatClient.prompt().user().call() → parse Plan JSON
 *       → per-file ChatClient calls → normalize → audit
 *       → (pass | repair loop via ChatClient) → install → activate
 *
 * Spring AI-native: no @AiService proxies, no TokenStream callbacks.
 * Everything flows through {@code AgentFactory.call()} → {@code ChatClient.prompt().user().call().content()}.
 */
@Component
public class SkillGenerator {

    private static final Logger log = LoggerFactory.getLogger(SkillGenerator.class);

    // ── System prompts for each phase ───────────────────────────────

    private static final String PLAN_SYSTEM_PROMPT = """
        You are a skill architect for the Wuwei platform. Analyze the user's request and produce a structured JSON plan.

        Output ONLY valid JSON (no markdown fences, no explanation):
        {
          "skillId": "kebab-case-id",
          "runtime": "js|browser-js",
          "capabilities": ["ui", "storage", ...],
          "files": [
            {"path": "skill.json", "purpose": "manifest"},
            {"path": "ui/index.json", "purpose": "A2UI component tree"},
            {"path": "handlers/index.js", "purpose": "event handlers"},
            {"path": "sql:seed", "purpose": "database seed data (optional)"}
          ]
        }

        Rules:
        - skillId: lowercase letters + digits + hyphens, must start with a letter
        - runtime: "js" for simple sync skills, "browser-js" for async/3D/Canvas skills
        - capabilities: only declare what handlers.js actually uses
          (ui, storage, database, crypto, network, ai, websearch, file, os, threejs, canvas)
        - files: list every file that needs to be generated
        - "sql:seed" is a special path for database initialization SQL
        """;

    private static final String DRIFT_SYSTEM_PROMPT = """
        You are a code reviewer analyzing whether a skill modification has drifted from its original intent.

        Output ONLY valid JSON (no markdown fences):
        {
          "driftScore": 3.5,
          "retainedGoals": ["CRUD operations", "password encryption"],
          "lostGoals": ["export to CSV"],
          "newGoals": ["batch import"],
          "reason": "The modification adds batch import which was not in the original intent...",
          "recommendation": "approve|review|reject"
        }

        driftScore: 0-10 (0=no drift, 10=completely different)
        recommendation: "approve" (score<4), "review" (4-7), "reject" (>7)
        """;

    // ── Fields ──────────────────────────────────────────────────────

    private final AgentFactory agentFactory;
    private final SkillMemoryService memoryService;
    private final StoreService storeService;
    private final Normalizer normalizer;
    private final AstAuditor astAuditor;
    private final EcosystemGuardian guardian;
    private final SkillManager skillManager;
    private final SkillIndexer skillIndexer;
    private final SnapshotService snapshotService;
    private final EventBus eventBus;
    private final ObjectMapper mapper;
    private final ConversationService conversationService;
    private volatile BiConsumer<String, Map<String, Object>> onMessageUpdate;
    private final String skillsBaseDir;
    private final int maxRepairAttempts;
    private final ExecutorService memoryExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public SkillGenerator(AgentFactory agentFactory,
                          SkillMemoryService memoryService, StoreService storeService,
                          Normalizer normalizer,
                          AstAuditor astAuditor, EcosystemGuardian guardian,
                          SkillManager skillManager, SkillIndexer skillIndexer,
                          SnapshotService snapshotService,
                          EventBus eventBus, ObjectMapper mapper,
                          ConversationService conversationService) {
        this.agentFactory = agentFactory;
        this.memoryService = memoryService;
        this.storeService = storeService;
        this.normalizer = normalizer;
        this.astAuditor = astAuditor;
        this.guardian = guardian;
        this.skillManager = skillManager;
        this.skillIndexer = skillIndexer;
        this.snapshotService = snapshotService;
        this.eventBus = eventBus;
        this.mapper = mapper;
        this.conversationService = conversationService;
        this.maxRepairAttempts = 10;

        String home = System.getProperty("user.home");
        this.skillsBaseDir = Paths.get(home, ".wuwei", "skills").toString();
    }

    // ── Public API ──────────────────────────────────────────────────

    public void setOnMessageUpdate(BiConsumer<String, Map<String, Object>> callback) {
        this.onMessageUpdate = callback;
    }

    public boolean enabled() {
        return agentFactory != null && agentFactory.enabled();
    }

    public String generate(String intent) { return generate(intent, null); }

    public String generate(String intent, Map<String, String> modelOverride) {
        return generate(intent, modelOverride, null);
    }

    public String generate(String intent, Map<String, String> modelOverride, String threadId) {
        return generate(intent, modelOverride, threadId, null);
    }

    public String generate(String intent, Map<String, String> modelOverride,
                           String threadId, String genMsgId) {
        if (!enabled()) {
            stepUpdate(threadId, genMsgId, "generating", "生成技能代码", "error",
                "LLM 服务未初始化，无法生成 Skill");
            return null;
        }

        String existingSummary = existingSkillsSummary();
        String tmpSkillId = "new-" + UUID.randomUUID().toString().substring(0, 8);
        return generateViaLlm(intent, existingSummary, tmpSkillId,
            modelOverride, null, null, threadId, genMsgId);
    }

    public String refine(String skillId, String feedback) {
        return refine(skillId, feedback, null);
    }

    public String refine(String skillId, String feedback, Map<String, String> modelOverride) {
        return refine(skillId, feedback, modelOverride, null);
    }

    public String refine(String skillId, String feedback, Map<String, String> modelOverride,
                         String threadId) {
        return refine(skillId, feedback, modelOverride, threadId, null);
    }

    public String refine(String skillId, String feedback, Map<String, String> modelOverride,
                         String threadId, String genMsgId) {
        if (!enabled()) {
            stepUpdate(threadId, genMsgId, "generating", "优化技能代码", "error",
                "LLM 服务未初始化，无法优化 Skill");
            return null;
        }

        Path skillDir = Paths.get(skillsBaseDir, skillId);
        Path genomeDir = skillDir.resolve("genome");
        if (!Files.isDirectory(skillDir)) {
            stepUpdate(threadId, genMsgId, "generating", "优化技能代码", "error",
                "Skill 目录不存在: " + skillId);
            return null;
        }

        String skillJson, uiJson, handlersJs;
        try {
            skillJson = Files.readString(skillDir.resolve("skill.json"));
            uiJson = genomeDir.resolve("ui.json").toFile().exists()
                ? Files.readString(genomeDir.resolve("ui.json"))
                : Files.readString(genomeDir.resolve("ui/index.json"));
            handlersJs = genomeDir.resolve("handlers.js").toFile().exists()
                ? Files.readString(genomeDir.resolve("handlers.js"))
                : Files.readString(genomeDir.resolve("handlers/index.js"));
        } catch (Exception e) {
            stepUpdate(threadId, genMsgId, "generating", "优化技能代码", "error",
                "无法读取 Skill 文件: " + e.getMessage());
            return null;
        }

        return refineViaLlm(skillId, feedback, skillJson, uiJson, handlersJs,
            modelOverride, threadId, genMsgId);
    }

    // ── LLM path: generate ──────────────────────────────────────────

    private String generateViaLlm(String intent, String existingSummary, String skillId,
                                   Map<String, String> modelOverride,
                                   Map<String, Object> memoryCtx,
                                   Map<String, String> currentFiles,
                                   String threadId, String genMsgId) {

        Map<String, String> routing = storeService.getModelRouting("skill/generate");
        String modelDesc = modelOverride != null && modelOverride.containsKey("model")
            ? modelOverride.get("model")
            : routing.getOrDefault("model", "unknown");
        System.out.println("[kernel] [generate] routing: provider="
            + routing.getOrDefault("provider", "?") + " model=" + modelDesc
            + " apiUrl=" + routing.getOrDefault("apiUrl", "?")
            + " hasApiKey=" + (!routing.getOrDefault("apiKey", "").isEmpty()));

        stepUpdate(threadId, genMsgId, "generating", "生成技能代码", "in_progress",
            "正在生成 Skill（" + routing.getOrDefault("provider", "") + "/" + modelDesc + "）...");

        String userMessage = PromptBuilder.buildGenerate(intent,
            List.of(existingSummary.split("\n")), memoryCtx, currentFiles);

        try {
            Path workDir = Paths.get(skillsBaseDir, skillId + "-gen");
            if (Files.exists(workDir)) {
                try (var walk = Files.walk(workDir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                }
            }
            Files.createDirectories(workDir);

            // Seed docs/ directory from classpath resources
            seedDocs(workDir);

            // Create memory/ directory for agent working notes
            Path memoryDir = workDir.resolve("memory");
            Files.createDirectories(memoryDir);

            String provider = routing.getOrDefault("provider", "");

            // ── RAG: find similar skills for reference ──
            String ragContext = "";
            if (skillIndexer != null) {
                try {
                    var similar = skillIndexer.search(intent, 3);
                    if (!similar.isEmpty()) {
                        var sb = new StringBuilder();
                        sb.append("现有技能参考（可复用其代码模式）：\n");
                        for (var s : similar) {
                            sb.append("- ").append(s.get("skillId"))
                              .append(" (相关度 ").append(s.get("relevance")).append("): ")
                              .append(s.get("reason")).append("\n");
                        }
                        ragContext = sb.toString();
                        System.out.println("[kernel] [generate] RAG found " + similar.size()
                            + " similar skills");
                    }
                } catch (Exception e) {
                    log.warn("RAG retrieval failed: {}", e.getMessage());
                }
            }

            // ── Phase 1: PLAN ──
            genLog(threadId, genMsgId, "plan", "", "分析需求，制定计划...");
            System.out.println("[kernel] [generate] Planner starting, provider=" + provider);
            stepUpdate(threadId, genMsgId, "generating", "生成技能代码", "in_progress",
                "制定计划中...");

            String planMessage = ragContext.isEmpty()
                ? userMessage
                : ragContext + "\n用户需求: " + userMessage;
            String planText = agentFactory.call(PLAN_SYSTEM_PROMPT, planMessage,
                "skill/generate", modelOverride);
            System.out.println("[kernel] [generate] Plan: " + planText);
            Plan plan = parsePlan(planText);
            if (plan == null) {
                stepUpdate(threadId, genMsgId, "generating", "生成技能代码", "error",
                    "计划解析失败");
                return null;
            }
            genLog(threadId, genMsgId, "plan", "", "计划: " + plan.files().size() + " 个文件");

            // ── Phase 2: EXECUTE ──
            Path seedDb = workDir.resolve("seed.db");
            java.sql.Connection seedConn = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + seedDb.toString());
            seedConn.createStatement().execute("PRAGMA journal_mode=WAL");

            for (Plan.FileSpec file : plan.files()) {
                genLog(threadId, genMsgId, "exec", file.path(), file.purpose());
                stepUpdate(threadId, genMsgId, "generating", "生成技能代码", "in_progress",
                    "生成 " + file.path());

                if (file.path().startsWith("sql:")) {
                    // SQL seed: execute directly against sandbox DB
                    String dbPrompt = buildDbSeedPrompt(file, plan, intent);
                    String sql = agentFactory.call(dbPrompt,
                        "输出SQL，每条以;结尾，不要解释",
                        "skill/generate", modelOverride);
                    if (sql != null && !sql.isBlank()) {
                        sql = stripMarkdownFences(sql);
                        int count = 0;
                        for (String stmt : sql.split(";")) {
                            stmt = stmt.trim();
                            if (!stmt.isEmpty() && !stmt.startsWith("--")) {
                                try { seedConn.createStatement().execute(stmt); count++; }
                                catch (Exception e) {
                                    System.out.println("[kernel] [generate] SQL skip: "
                                        + e.getMessage());
                                }
                            }
                        }
                        genLog(threadId, genMsgId, "sql", "", count + " 条SQL已执行");
                    }
                } else {
                    // File generation: any path, any format
                    String filePrompt = buildFilePrompt(file, plan, intent);
                    String content = agentFactory.call(filePrompt,
                        "输出 " + file.path() + " 的内容",
                        "skill/generate", modelOverride);
                    if (content != null && !content.isBlank()) {
                        content = stripMarkdownFences(content);
                        writeFile(workDir, file.path(), content);
                        genLog(threadId, genMsgId, "createFile", file.path(), null);
                    } else {
                        System.out.println("[kernel] [generate] WARNING: empty response for "
                            + file.path());
                    }
                }
            }
            seedConn.close();

            // Read files from working directory
            Map<String, String> allFiles = readAllFiles(workDir);
            SkillFiles files = buildSkillFiles(allFiles, skillId);

            files = ensureRequiredFiles(files, skillId, intent);
            stepUpdate(threadId, genMsgId, "generating", "生成技能代码", "done", "生成完成");

            // Extract agent's working notes for design decision recording
            String designDecision = null;
            try {
                Path notesPath = workDir.resolve("memory").resolve("notes.md");
                if (Files.exists(notesPath)) {
                    designDecision = Files.readString(notesPath);
                }
            } catch (Exception ignored) {}

            String resultId = auditAndInstall(normalizer.normalize(files), skillId,
                modelOverride, intent, "Initial Design", designDecision,
                threadId, genMsgId);

            // Copy seed data from staging to installed skill
            if (resultId != null) {
                copySeedData(workDir, Paths.get(skillsBaseDir, resultId));
            }
            return resultId;

        } catch (Exception e) {
            System.out.println("[kernel] [generate] EXCEPTION: " + e.getClass().getName()
                + ": " + (e.getMessage() != null ? e.getMessage() : "(null)"));
            e.printStackTrace(System.out);
            log.error("LLM generation failed", e);
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) {
                Throwable cause = e.getCause();
                msg = e.getClass().getSimpleName()
                    + (cause != null
                        ? " caused by " + cause.getClass().getSimpleName() + ": "
                          + cause.getMessage()
                        : "");
            }
            eventBus.publish(new KernelEvent.PlanStep("error",
                "LLM 生成失败: " + msg, threadId));
            stepUpdate(threadId, genMsgId, "generating", "生成技能代码", "error",
                "LLM 调用失败: " + msg);
            return null;
        } finally {
            Path stagingDir = Paths.get(skillsBaseDir, skillId + "-gen");
            cleanupDir(stagingDir);
            System.out.println("[kernel] [generate] Cleaned up staging dir: "
                + stagingDir.getFileName());
        }
    }

    // ── LLM path: refine ────────────────────────────────────────────

    private String refineViaLlm(String skillId, String feedback,
                                 String skillJson, String uiJson, String handlersJs,
                                 Map<String, String> modelOverride, String threadId,
                                 String genMsgId) {

        Map<String, String> routing = storeService.getModelRouting("skill/generate");
        String modelDesc = modelOverride != null && modelOverride.containsKey("model")
            ? modelOverride.get("model")
            : routing.getOrDefault("model", "unknown");

        stepUpdate(threadId, genMsgId, "generating", "优化技能代码", "in_progress",
            "正在优化 Skill " + skillId + "（"
                + routing.getOrDefault("provider", "") + "/" + modelDesc + "）...");

        try {
            Path workDir = Paths.get(skillsBaseDir, skillId + "-refine");
            if (Files.exists(workDir)) {
                try (var walk = Files.walk(workDir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                }
            }
            Files.createDirectories(workDir);
            // Seed existing files so the LLM can read them
            Files.writeString(workDir.resolve("skill.json"), skillJson);
            Files.writeString(workDir.resolve("ui.json"), uiJson);
            Files.writeString(workDir.resolve("handlers.js"), handlersJs);

            // Plan which files to update
            String refinePrompt = "Optimize existing skill " + skillId
                + " based on feedback: " + feedback;
            String planText = agentFactory.call(PLAN_SYSTEM_PROMPT, refinePrompt,
                "skill/generate", modelOverride);
            Plan plan = parsePlan(planText);

            if (plan == null || plan.files().isEmpty()) {
                return auditAndInstall(normalizer.normalize(
                    new SkillFiles(skillJson, uiJson, handlersJs)), skillId,
                    modelOverride, null, "Refine", null, threadId, genMsgId);
            }

            // Execute: regenerate each file listed in the plan
            for (Plan.FileSpec file : plan.files()) {
                stepUpdate(threadId, genMsgId, "generating", "优化技能代码", "in_progress",
                    "优化 " + file.path());
                String filePrompt = "优化以下文件，根据反馈: " + feedback + "\n\n"
                    + "当前内容:\n"
                    + (file.path().contains("ui")
                        ? uiJson
                        : file.path().contains("handler") ? handlersJs : skillJson);
                String content = agentFactory.call(filePrompt,
                    "输出 " + file.path() + " 的新内容",
                    "skill/generate", modelOverride);
                if (content != null && !content.isBlank()) {
                    writeFile(workDir, file.path(), stripMarkdownFences(content));
                }
            }

            Map<String, String> allFiles = readAllFiles(workDir);
            SkillFiles rawFiles = buildSkillFiles(allFiles, skillId);
            SkillFiles rawFixed = new SkillFiles(
                forceSkillId(rawFiles.skillJson(), skillId),
                rawFiles.uiJson(), rawFiles.handlersJs(),
                rawFiles.handlerModules(), rawFiles.uiFragments());

            SkillFiles normalized = normalizer.normalize(rawFixed);
            String designTitle = "Refine: "
                + feedback.substring(0, Math.min(60, feedback.length()));

            return auditAndInstall(normalized, skillId, modelOverride,
                null, designTitle, null, threadId, genMsgId);

        } catch (Exception e) {
            log.error("LLM refine failed for {}", skillId, e);
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) {
                Throwable cause = e.getCause();
                msg = e.getClass().getSimpleName()
                    + (cause != null
                        ? " caused by " + cause.getClass().getSimpleName() + ": "
                          + cause.getMessage()
                        : "");
            }
            eventBus.publish(new KernelEvent.PlanStep("error",
                "LLM 优化失败: " + msg, threadId));
            stepUpdate(threadId, genMsgId, "generating", "优化技能代码", "error",
                "LLM 优化失败: " + msg);
            return null;
        } finally {
            Path stagingDir = Paths.get(skillsBaseDir, skillId + "-refine");
            cleanupDir(stagingDir);
            System.out.println("[kernel] [refine] Cleaned up staging dir: "
                + stagingDir.getFileName());
        }
    }

    // ── LLM repair ───────────────────────────────────────────────────

    private SkillFiles llmRepair(SkillFiles current, String skillId, String error,
                                  int attempt, Map<String, String> modelOverride) {
        String originalIntent = memoryService.readIntent(skillId);
        String userMessage = PromptBuilder.buildRepair(error, current, originalIntent, attempt);

        try {
            String repairSystemPrompt = loadRepairPrompt();
            String raw = agentFactory.call(repairSystemPrompt, userMessage,
                "skill/generate", modelOverride);
            if (raw == null || raw.isBlank()) {
                throw new RuntimeException("LLM 修复返回空响应");
            }
            return parseThreeFiles(raw);
        } catch (Exception e) {
            log.error("Repair call failed for {}", skillId, e);
            throw new RuntimeException("LLM 修复失败: " + e.getMessage(), e);
        }
    }

    // ── Audit + Repair loop + Install ───────────────────────────────

    private String auditAndInstall(SkillFiles initialFiles, String skillId,
                                    Map<String, String> modelOverride,
                                    String intent, String designTitle, String designDecision,
                                    String threadId, String genMsgId) {
        stepUpdate(threadId, genMsgId, "normalizing", "规范化输出", "in_progress",
            "正在规范化输出...");
        SkillFiles finalFiles = initialFiles;

        for (int attempt = 0; attempt <= maxRepairAttempts; attempt++) {
            try {
                SkillManifest manifest;
                try {
                    manifest = mapper.readValue(finalFiles.skillJson(), SkillManifest.class);
                } catch (Exception e) {
                    throw new GateException("INVALID_MANIFEST",
                        "skill.json 解析失败: " + e.getMessage());
                }

                SkillGenome genome = new SkillGenome(finalFiles.uiJson(),
                    finalFiles.handlersJs(), finalFiles.handlerModules());

                stepUpdate(threadId, genMsgId, "auditing", "安全审计", "in_progress",
                    "正在审计 Skill " + manifest.id() + "...");

                // DEBUG: find all lines containing "0x" to see raw hex color values
                String js = finalFiles.handlersJs();
                if (js != null) {
                    String[] lines = js.split("\n");
                    for (int li = 0; li < lines.length; li++) {
                        if (lines[li].contains("0x")) {
                            System.out.println("[audit-debug] attempt=" + attempt + " line"
                                + (li + 1) + " 0x found: " + lines[li].trim());
                        }
                    }
                }

                astAuditor.audit(manifest, genome);
                guardian.check(manifest.id(), genome);

                // Drift check on refine (not first generation)
                String currentSkillId = manifest.id();
                String originalIntent = memoryService.readIntent(currentSkillId);
                if (originalIntent != null && !originalIntent.isBlank()) {
                    runDriftCheck(currentSkillId, originalIntent,
                        finalFiles.handlersJs(), "audit-passed", modelOverride);
                }

                String installedId = installSkill(manifest, finalFiles, threadId, genMsgId);
                if (installedId != null) {
                    memoryExecutor.submit(() ->
                        persistMemory(installedId, intent, designTitle, designDecision));
                }
                return installedId;

            } catch (GateException e) {
                String errorDetail = "[" + e.getCode() + "] " + e.getMessage();
                log.warn("Audit failed (attempt {}): {}", attempt + 1, errorDetail);
                System.out.println("[audit] FAIL attempt=" + (attempt + 1)
                    + " code=" + e.getCode() + " msg=" + e.getMessage());

                String realSkillId = extractSkillId(finalFiles.skillJson());
                eventBus.publish(new KernelEvent.RepairAttempt(realSkillId, attempt + 1,
                    errorDetail));

                if (attempt >= maxRepairAttempts) {
                    stepUpdate(threadId, genMsgId, "repairing", "自动修复", "error",
                        "修复已耗尽（" + maxRepairAttempts + " 次），最后的错误: "
                            + errorDetail);
                    return null;
                }

                stepUpdate(threadId, genMsgId, "repairing", "自动修复", "in_progress",
                    "第 " + (attempt + 1) + "/" + maxRepairAttempts + " 次修复中: "
                        + e.getCode());

                try {
                    SkillFiles repaired = llmRepair(finalFiles, realSkillId,
                        errorDetail, attempt + 1, modelOverride);
                    SkillFiles repairedFixed = new SkillFiles(
                        forceSkillId(repaired.skillJson(), realSkillId),
                        repaired.uiJson(),
                        repaired.handlersJs());
                    finalFiles = normalizer.normalize(repairedFixed);
                } catch (Exception repairEx) {
                    log.error("Repair call failed", repairEx);
                    stepUpdate(threadId, genMsgId, "repairing", "自动修复", "error",
                        "修复调用失败: " + repairEx.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    // ── Drift check ──────────────────────────────────────────────────

    private void runDriftCheck(String skillId, String originalIntent,
                                String currentHandlersJs, String proposedChange,
                                Map<String, String> modelOverride) {
        try {
            String userMessage = PromptBuilder.buildDriftAnalysis(
                originalIntent, List.of(), currentHandlersJs, proposedChange);
            String raw = agentFactory.call(DRIFT_SYSTEM_PROMPT, userMessage,
                "skill/generate", modelOverride);
            if (raw == null || raw.isBlank()) return;

            DriftResult result = parseDriftResult(raw);
            log.info("Drift check for {}: score={}, recommendation={}",
                skillId, result.driftScore(), result.recommendation());

            storeService.recordDrift(skillId, null, null,
                result.driftScore(),
                String.join(",", result.retainedGoals()),
                String.join(",", result.lostGoals()),
                String.join(",", result.newGoals()),
                result.reason(), result.recommendation());

            if ("reject".equals(result.recommendation()) || result.driftScore() > 7) {
                eventBus.publish(new KernelEvent.SystemNotify(
                    "意图漂移警告",
                    "Skill " + skillId + " 的修改偏离了原始意图（评分 "
                        + String.format("%.1f", result.driftScore()) + "/10）"));
            }
        } catch (Exception e) {
            log.warn("Drift check failed for {}: {}", skillId, e.getMessage());
        }
    }

    // ── Memory persistence ───────────────────────────────────────────

    private void persistMemory(String skillId, String intent,
                                String designTitle, String designDecision) {
        if (intent != null) {
            try {
                memoryService.writeIntent(skillId, intent);
            } catch (IOException e) {
                log.debug("Intent already locked for {}: {}", skillId, e.getMessage());
            }
        }
        String entry = designDecision != null && !designDecision.isBlank()
            ? designDecision
            : (designTitle != null ? designTitle : "Design Decision");
        String title = designTitle != null ? designTitle : "Design Decision";
        memoryService.appendDesign(skillId, title, entry);
    }

    // ── Install generated skill ─────────────────────────────────────

    private String installSkill(SkillManifest manifest, SkillFiles files,
                                 String threadId, String genMsgId) {
        String skillId = manifest.id();

        stepUpdate(threadId, genMsgId, "installing", "安装部署", "in_progress",
            "正在安装 Skill: " + skillId + "...");

        try {
            Path skillDir = Paths.get(skillsBaseDir, skillId);
            Path genomeDir = skillDir.resolve("genome");

            // Clean stale artifacts but preserve memory/
            if (Files.exists(skillDir)) {
                for (String name : List.of("skill.json", "genome", "phenotype")) {
                    Path p = skillDir.resolve(name);
                    if (Files.isDirectory(p)) {
                        try (Stream<Path> walk = Files.walk(p)) {
                            walk.sorted(Comparator.reverseOrder())
                                .forEach(x -> { try { Files.delete(x); }
                                                catch (Exception ignored) {} });
                        }
                    } else {
                        try { Files.delete(p); } catch (Exception ignored) {}
                    }
                }
            }
            Files.createDirectories(genomeDir);

            String prettySkillJson = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(manifest);
            Files.writeString(skillDir.resolve("skill.json"), prettySkillJson);

            // Write UI files: multi-fragment or single-file
            if (files.uiFragments() != null && !files.uiFragments().isEmpty()) {
                Path uiDir = genomeDir.resolve("ui");
                Files.createDirectories(uiDir);
                for (var entry : files.uiFragments().entrySet()) {
                    Path fragPath = uiDir.resolve(entry.getKey());
                    Files.createDirectories(fragPath.getParent());
                    String pretty = mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(mapper.readTree(entry.getValue()));
                    Files.writeString(fragPath, pretty);
                }
            } else {
                String prettyUiJson = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(mapper.readTree(files.uiJson()));
                Files.writeString(genomeDir.resolve("ui.json"), prettyUiJson);
            }

            // Write handler files: multi-module or single-file
            if (files.handlerModules() != null && !files.handlerModules().isEmpty()) {
                Path handlersDir = genomeDir.resolve("handlers");
                Files.createDirectories(handlersDir);
                for (var entry : files.handlerModules().entrySet()) {
                    Path modPath = handlersDir.resolve(entry.getKey());
                    Files.createDirectories(modPath.getParent());
                    Files.writeString(modPath, entry.getValue());
                }
            } else {
                Files.writeString(genomeDir.resolve("handlers.js"), files.handlersJs());
            }

            log.info("Generated skill files written to {}", skillDir);

            snapshotService.delete(skillId);
            eventBus.publish(new KernelEvent.SkillLoading(skillId));

            try {
                skillManager.loadFromDirectory(skillDir);
            } catch (Exception e) {
                log.error("Failed to load generated skill {}: {}", skillId, e.getMessage());
                stepUpdate(threadId, genMsgId, "installing", "安装部署", "error",
                    "Skill 加载失败: " + e.getMessage());
                return null;
            }

            // Mark all steps as done before activation
            for (String key : List.of("generating", "normalizing", "auditing",
                "repairing", "installing")) {
                stepUpdate(threadId, genMsgId, key, stepLabel(key), "done",
                    "Skill " + skillId + " 已生成并激活");
            }

            // Push final generation card with skillId + allDone
            if (onMessageUpdate != null && genMsgId != null) {
                generationLogs.computeIfAbsent(genMsgId, k -> new ArrayList<>());
                Map<String, String> finalEntry = new LinkedHashMap<>();
                finalEntry.put("time", timeStr());
                finalEntry.put("action", "done");
                finalEntry.put("path", skillId);
                finalEntry.put("detail", "");
                generationLogs.get(genMsgId).add(finalEntry);
                pushGenerationCard(threadId, genMsgId, skillId, true, null);
            }

            skillManager.activate(skillId, threadId);

            // Final push
            if (onMessageUpdate != null && genMsgId != null) {
                pushGenerationCard(threadId, genMsgId, skillId, true, null);
            }
            return skillId;

        } catch (Exception e) {
            log.error("Failed to install generated skill {}: {}", skillId, e.getMessage());
            stepUpdate(threadId, genMsgId, "installing", "安装部署", "error",
                "安装失败: " + e.getMessage());
            return null;
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private String timeStr() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static String stepLabel(String key) {
        return switch (key) {
            case "generating" -> "生成技能代码";
            case "normalizing" -> "规范化输出";
            case "auditing" -> "安全审计";
            case "repairing" -> "自动修复";
            case "installing" -> "安装部署";
            default -> key;
        };
    }

    /** Parse the Planner's JSON output into a Plan object. */
    private Plan parsePlan(String planText) {
        if (planText == null || planText.isBlank()) return null;
        try {
            String json = planText.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n');
                int end = json.lastIndexOf("```");
                if (start >= 0 && end >= 0) json = json.substring(start, end).trim();
            }
            if (json.startsWith("{")) {
                int braceEnd = json.lastIndexOf('}');
                if (braceEnd >= 0) json = json.substring(0, braceEnd + 1);
            }
            JsonNode root = mapper.readTree(json);
            String skillId = root.has("skillId") ? root.get("skillId").asText() : "";
            String runtime = root.has("runtime") ? root.get("runtime").asText() : "js";
            List<String> capabilities = new ArrayList<>();
            if (root.has("capabilities")) {
                for (JsonNode c : root.get("capabilities")) capabilities.add(c.asText());
            }
            List<Plan.FileSpec> files = new ArrayList<>();
            if (root.has("files")) {
                for (JsonNode f : root.get("files")) {
                    files.add(new Plan.FileSpec(
                        f.get("path").asText(),
                        f.has("purpose") ? f.get("purpose").asText() : ""));
                }
            }
            return new Plan(skillId, runtime, capabilities, files);
        } catch (Exception e) {
            System.out.println("[kernel] [generate] Failed to parse plan: " + e.getMessage());
            return null;
        }
    }

    /** Parse LLM repair/refine output: "=== file === ... === file ===" format. */
    private SkillFiles parseThreeFiles(String raw) {
        String skillJson = extractSection(raw, "skill.json");
        // If single-file output (just JSON, no separators), wrap in a minimal skill
        if (skillJson == null || skillJson.isBlank()) {
            // Try to find any JSON-like content
            String trimmed = raw.trim();
            if (trimmed.startsWith("```")) {
                trimmed = stripMarkdownFences(trimmed);
            }
            if (trimmed.startsWith("{")) {
                // It might be a complete skill.json — assume the LLM output just skill.json
                String uiSection = extractSection(raw, "ui.json");
                String jsSection = extractSection(raw, "handlers.js");
                return new SkillFiles(trimmed,
                    uiSection != null ? uiSection : "{}",
                    jsSection != null ? jsSection : "");
            }
            return new SkillFiles(skillJson, "{}", "");
        }
        String uiJson = extractSection(raw, "ui.json");
        String handlersJs = extractSection(raw, "handlers.js");
        return new SkillFiles(skillJson,
            uiJson != null ? uiJson : "{}",
            handlersJs != null ? handlersJs : "");
    }

    /** Extract a named section from "=== name ===\n...content..." format. */
    private String extractSection(String raw, String name) {
        String marker = "=== " + name + " ===";
        int start = raw.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        if (start < raw.length() && raw.charAt(start) == '\r') start++;
        if (start < raw.length() && raw.charAt(start) == '\n') start++;

        // Find the next section marker or end of string
        int end = raw.indexOf("\n===", start);
        if (end < 0) end = raw.length();

        return raw.substring(start, end).trim();
    }

    /** Parse drift analysis LLM output into DriftResult. */
    private DriftResult parseDriftResult(String raw) {
        try {
            String json = raw.trim();
            if (json.startsWith("```")) {
                json = stripMarkdownFences(json);
            }
            if (json.startsWith("{")) {
                int braceEnd = json.lastIndexOf('}');
                if (braceEnd >= 0) json = json.substring(0, braceEnd + 1);
            }
            return mapper.readValue(json, DriftResult.class);
        } catch (Exception e) {
            log.warn("Failed to parse drift result: {}", e.getMessage());
            return new DriftResult(0, List.of(), List.of(), List.of(),
                "Parse error: " + e.getMessage(), "approve");
        }
    }

    /** Build a context-rich prompt for generating a single file. */
    private String buildFilePrompt(Plan.FileSpec file, Plan plan, String intent) {
        var sb = new StringBuilder();
        sb.append("你是无为平台（Wuwei）的技能开发者。\n\n");
        sb.append("用户需求: ").append(intent).append("\n");
        sb.append("技能ID: ").append(plan.skillId()).append("\n");
        sb.append("运行时: ").append(plan.runtime()).append("\n");
        sb.append("声明能力: ").append(String.join(", ", plan.capabilities())).append("\n\n");

        if (file.path().contains("skill.json")) {
            sb.append("""
                输出 skill.json 的完整内容。规则:
                - 精确7个顶级字段: id, version, abi, runtime, meta, capabilities, signature
                - id: kebab-case, version: X.Y.Z, abi: "1.0"
                - capabilities: JSON对象{}不是数组[], 只声明实际使用的能力
                - signature: 必须是 {"publisher":"local"}
                - runtime: js=browser-js""");
        } else if (file.path().contains("ui")) {
            sb.append("""
                输出 ui/index.json 的完整内容。A2UI组件树格式:
                - 顶层 {"components": [...]} 数组
                - root组件必须是 id="root" component="Column"
                - Column/Row的children是id字符串数组, Button的child是单个id字符串
                - Button的action.event.name必须等于Button的id
                - 所有children/child引用的id必须存在""");
        } else if (file.path().contains("handler")) {
            sb.append("""
                输出 handlers/index.js 的完整内容。规则:
                - 顶层函数自动注册, 禁止 module.exports
                - JS运行时: 禁止 async/await/Promise/eval/Function/fetch/WebSocket
                - 函数签名: function onXxx(__inputs__, capability)
                - Button "my-btn" -> function onMyBtn
                - 用capability.ui.set(id,"text",value)更新Text文字""");
        } else {
            sb.append("输出 ").append(file.path()).append(" 的完整内容。\n");
        }

        sb.append("\n只输出文件内容本身，不要Markdown代码块，不要解释。");
        return sb.toString();
    }

    /** Build a prompt for database seeding. */
    private String buildDbSeedPrompt(Plan.FileSpec file, Plan plan, String intent) {
        return "你是无为平台（Wuwei）的数据库管理员。\n\n"
            + "用户需求: " + intent + "\n"
            + "技能ID: " + plan.skillId() + "\n\n"
            + "用途: " + file.purpose() + "\n\n"
            + "请输出纯SQL语句（SQLite语法），每条语句以分号结尾。\n"
            + "- CREATE TABLE IF NOT EXISTS 建表\n"
            + "- INSERT INTO 插入数据\n"
            + "- 字符串用单引号，中文直接写\n"
            + "- 不要Markdown代码块，不要解释，只输出SQL";
    }

    /** Strip markdown fences from LLM output. */
    private static String stripMarkdownFences(String content) {
        return content
            .replaceFirst("^```(?:json|js|sql)?\\s*\\n?", "")
            .replaceFirst("\\n?```\\s*$", "")
            .trim();
    }

    /** Load repair system prompt from classpath resource. */
    private String loadRepairPrompt() {
        try {
            var is = getClass().getClassLoader().getResourceAsStream("prompts/repair.txt");
            if (is != null) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                is.close();
                return content;
            }
        } catch (Exception e) {
            log.warn("Failed to load repair prompt: {}", e.getMessage());
        }
        // Fallback: concise repair instructions
        return """
            你是无为平台（Wuwei）的 Skill 修复器。下面的 Skill 没有通过安全审计，请修复后重新输出。

            输出格式（严格遵守）：
            === skill.json ===
            {...}
            === ui.json ===
            {"components": [...]}
            === handlers.js ===
            ...代码...

            只输出三个文件，不要Markdown代码块，不要解释。""";
    }

    // ── File I/O helpers (replaces SkillFileTools) ─────────────────

    /** Write content to a file in the working directory, creating parent dirs. */
    private void writeFile(Path workDir, String relativePath, String content) {
        try {
            Path target = workDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        } catch (IOException e) {
            log.warn("Failed to write {}: {}", relativePath, e.getMessage());
        }
    }

    /** Read all files from working directory into a path→content map. */
    private Map<String, String> readAllFiles(Path workDir) {
        Map<String, String> all = new LinkedHashMap<>();
        try {
            if (Files.isDirectory(workDir)) {
                try (var walk = Files.walk(workDir)) {
                    walk.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().endsWith(".db"))
                        .forEach(p -> {
                            try {
                                String rel = workDir.relativize(p).toString()
                                    .replace('\\', '/');
                                all.put(rel, Files.readString(p));
                            } catch (Exception ignored) {}
                        });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read work dir: {}", e.getMessage());
        }
        return all;
    }

    // ── Streaming log / generation card ─────────────────────────────

    private final ConcurrentHashMap<String, Map<String, Map<String, String>>> generationSteps
        = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Map<String, String>>> generationLogs
        = new ConcurrentHashMap<>();

    private void genLog(String threadId, String genMsgId, String action,
                        String path, String detail) {
        if (threadId == null || genMsgId == null || onMessageUpdate == null) return;
        generationLogs.computeIfAbsent(genMsgId, k -> new ArrayList<>());
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("time", timeStr());
        entry.put("action", action);
        entry.put("path", path != null ? path : "");
        entry.put("detail", detail != null ? detail : "");
        generationLogs.get(genMsgId).add(entry);
    }

    private void pushGenerationCard(String threadId, String genMsgId,
                                     String skillId, boolean allDone, String error) {
        if (onMessageUpdate == null) return;
        Map<String, Map<String, String>> genSteps = generationSteps.get(genMsgId);
        List<Map<String, String>> stepsList = new ArrayList<>();
        if (genSteps != null) {
            for (String k : new String[]{"generating", "normalizing", "auditing",
                "repairing", "installing"}) {
                if (genSteps.containsKey(k)) stepsList.add(genSteps.get(k));
            }
        }
        List<Map<String, String>> logs = generationLogs.getOrDefault(genMsgId, List.of());
        List<Map<String, String>> trimmed = logs.size() > 50
            ? new ArrayList<>(logs.subList(logs.size() - 50, logs.size())) : logs;

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", genMsgId);
        msg.put("role", "assistant");
        msg.put("content", "");
        msg.put("time", timeStr());
        msg.put("type", "generation");
        msg.put("steps", stepsList);
        msg.put("log", trimmed);
        msg.put("skillId", skillId);
        msg.put("allDone", allDone);
        if (error != null) msg.put("error", error);

        onMessageUpdate.accept(threadId, msg);
    }

    private void stepUpdate(String threadId, String genMsgId, String key,
                             String label, String status, String desc) {
        if (threadId == null || threadId.isEmpty() || genMsgId == null) return;
        if (onMessageUpdate != null) {
            generationSteps.computeIfAbsent(genMsgId, k -> new LinkedHashMap<>());
            Map<String, String> step = new LinkedHashMap<>();
            step.put("key", key);
            step.put("label", label);
            step.put("status", status);
            generationSteps.get(genMsgId).put(key, step);

            boolean allDone = generationSteps.get(genMsgId).values().stream()
                .allMatch(s -> "done".equals(s.get("status")));
            boolean hasError = generationSteps.get(genMsgId).values().stream()
                .anyMatch(s -> "error".equals(s.get("status")));
            pushGenerationCard(threadId, genMsgId, null, allDone,
                hasError ? desc : null);
        }
        eventBus.publish(new KernelEvent.PlanStep(status, desc, threadId));
    }

    // ── File assembly helpers ───────────────────────────────────────

    private SkillFiles buildSkillFiles(Map<String, String> allFiles, String skillId) {
        String skillJson = allFiles.getOrDefault("skill.json", "");
        if (skillJson.isEmpty()) {
            skillJson = "{ \"id\": \"" + skillId.replace("new-", "skill-")
                + "\", \"version\": \"1.0.0\", \"abi\": \"1.0\", \"runtime\": \"js\","
                + " \"meta\": {}, \"capabilities\": {},"
                + " \"signature\": { \"publisher\": \"local\" } }";
        }
        String currentId = extractSkillId(skillJson);
        if (currentId.startsWith("new-")) {
            skillJson = forceSkillId(skillJson, currentId.replace("new-", "skill-"));
        }

        // Detect UI layout
        String uiJson;
        Map<String, String> uiFragments = null;
        if (allFiles.containsKey("ui/index.json")) {
            uiJson = allFiles.get("ui/index.json");
            uiFragments = new LinkedHashMap<>();
            for (var entry : allFiles.entrySet()) {
                if (entry.getKey().startsWith("ui/")) {
                    uiFragments.put(entry.getKey().substring(3), entry.getValue());
                }
            }
        } else if (allFiles.containsKey("ui.json")) {
            uiJson = allFiles.get("ui.json");
        } else {
            uiJson = "{}";
        }

        // Detect handlers layout
        String handlersJs;
        Map<String, String> handlerModules = null;
        if (allFiles.containsKey("handlers/index.js")) {
            handlersJs = allFiles.get("handlers/index.js");
            handlerModules = new LinkedHashMap<>();
            for (var entry : allFiles.entrySet()) {
                if (entry.getKey().startsWith("handlers/")) {
                    handlerModules.put(entry.getKey().substring(9), entry.getValue());
                }
            }
        } else if (allFiles.containsKey("handlers.js")) {
            handlersJs = allFiles.get("handlers.js");
        } else {
            handlersJs = "";
        }

        return new SkillFiles(skillJson, uiJson, handlersJs,
            handlerModules, uiFragments);
    }

    private SkillFiles ensureRequiredFiles(SkillFiles files, String skillId, String intent) {
        String uiJson = files.uiJson();
        String handlersJs = files.handlersJs();
        boolean patched = false;

        String displayId = extractSkillId(files.skillJson());
        if (displayId.equals("unknown") || displayId.startsWith("new-")) {
            displayId = skillId.replace("new-", "skill-");
        }

        if (uiJson == null || uiJson.isBlank() || uiJson.equals("{}")) {
            uiJson = "{"
                + "\"components\": [{"
                + "\"id\": \"root\","
                + "\"component\": \"Column\","
                + "\"children\": [\"title\"]"
                + "}, {"
                + "\"id\": \"title\","
                + "\"component\": \"Text\","
                + "\"text\": \"Skill: " + displayId + "\""
                + "}]}";
            patched = true;
        }

        if (handlersJs == null || handlersJs.isBlank()) {
            handlersJs = "// Skill: " + displayId + "\n"
                + "// Intent: " + (intent != null ? intent.replace("\n", " ") : "") + "\n\n"
                + "function onInit(__inputs__, capability) {\n"
                + "}\n";
            patched = true;
        }

        if (patched) {
            System.out.println("[kernel] [generate] Safety net: filled missing files for "
                + skillId);
        }

        return new SkillFiles(files.skillJson(), uiJson, handlersJs,
            files.handlerModules(), files.uiFragments());
    }

    private String existingSkillsSummary() {
        var skills = skillManager.listSkills();
        if (skills.isEmpty()) return "（暂无已安装的 Skill）";
        return skills.stream()
            .map(s -> "- " + s.id() + " (名称: " + s.name() + ", 版本: " + s.version() + ")")
            .collect(Collectors.joining("\n"));
    }

    private String forceSkillId(String skillJson, String skillId) {
        try {
            JsonNode root = mapper.readTree(skillJson);
            if (root.isObject()) {
                var obj = (com.fasterxml.jackson.databind.node.ObjectNode) root;
                obj.put("id", skillId);
                return mapper.writeValueAsString(obj);
            }
        } catch (Exception e) {
            log.warn("forceSkillId failed: {}", e.getMessage());
        }
        return skillJson;
    }

    private String extractSkillId(String skillJson) {
        try {
            return mapper.readTree(skillJson).get("id").asText();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Copy seed DB and data/ from staging to installed skill directory. */
    private void copySeedData(Path workDir, Path skillDir) {
        try {
            Path phenotypeDir = skillDir.resolve("phenotype");
            Files.createDirectories(phenotypeDir);
            Path seedDb = workDir.resolve("seed.db");
            if (Files.exists(seedDb)) {
                Files.copy(seedDb, phenotypeDir.resolve("data.db"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Path stgData = workDir.resolve("data");
            if (Files.isDirectory(stgData)) {
                Path dataDir = skillDir.resolve("genome").resolve("data");
                try (var walk = Files.walk(stgData)) {
                    for (Path f : walk.filter(Files::isRegularFile).toList()) {
                        Path rel = stgData.relativize(f);
                        Path target = dataDir.resolve(rel);
                        Files.createDirectories(target.getParent());
                        Files.copy(f, target,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[kernel] [generate] Seed data copy failed: "
                + e.getMessage());
        }
    }

    private void cleanupDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); }
                                        catch (Exception ignored) {} });
                }
            }
        } catch (Exception ignored) {}
    }

    /** Copy docs/ resources from classpath to workDir for agent reference. */
    private void seedDocs(Path workDir) {
        try {
            Path docsDir = workDir.resolve("docs");
            Files.createDirectories(docsDir);

            String[] docs = {
                "README.md", "rules.md",
                "a2ui-components.md", "a2ui-layout.md",
                "runtime-js.md",
                "examples/pagination-db.md",
                "examples/database-crud.md"
            };

            for (String doc : docs) {
                String resourcePath = "docs/" + doc;
                var is = getClass().getClassLoader().getResourceAsStream(resourcePath);
                if (is != null) {
                    Path target = docsDir.resolve(doc);
                    Files.createDirectories(target.getParent());
                    Files.copy(is, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    is.close();
                } else {
                    System.out.println("[kernel] [generate] WARNING: doc resource not found: "
                        + resourcePath);
                }
            }
            System.out.println("[kernel] [generate] Seeded docs/ into " + docsDir);
        } catch (Exception e) {
            System.out.println("[kernel] [generate] Failed to seed docs: " + e.getMessage());
        }
    }
}
