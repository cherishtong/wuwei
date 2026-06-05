package com.wuwei.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.wuwei.bus.EventBus;
import com.wuwei.bus.event.KernelEvent;
import com.wuwei.gate.AstAuditor;
import com.wuwei.rag.SkillIndexer;
import com.wuwei.gate.EcosystemGuardian;
import com.wuwei.gate.GateException;
import com.wuwei.skill.SkillGenome;
import com.wuwei.skill.SkillManager;
import com.wuwei.skill.SkillManifest;
import com.wuwei.snapshot.SnapshotService;
import com.wuwei.store.ConversationService;
import com.wuwei.store.SkillMemoryService;
import com.wuwei.store.StoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Skill generation pipeline via LangChain4j AiServices + ChatMemory.
 *
 * Flow: intent → AgentFactory → AiServices → TokenStream → parse → normalize
 *       → audit → (pass | repair loop) → install → load → activate
 *
 * Conversation memory is auto-managed by {@code @MemoryId} + ChatMemory —
 * no manual memory Map building.
 */
public class SkillGenerator {

    private static final Logger log = LoggerFactory.getLogger(SkillGenerator.class);

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
                          ConversationService conversationService,
                          BiConsumer<String, Map<String, Object>> onMessageUpdate,
                          int maxRepairAttempts) {
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
        this.onMessageUpdate = onMessageUpdate;
        this.maxRepairAttempts = maxRepairAttempts;

        String home = System.getProperty("user.home");
        this.skillsBaseDir = Paths.get(home, ".wuwei", "skills").toString();
    }

    // ── Public API ──────────────────────────────────────────────

    public void setOnMessageUpdate(BiConsumer<String, Map<String, Object>> callback) {
        this.onMessageUpdate = callback;
    }

    public boolean enabled() {
        return agentFactory != null;
    }

    public String generate(String intent) {
        return generate(intent, null);
    }

    public String generate(String intent, Map<String, String> modelOverride) {
        return generate(intent, modelOverride, null);
    }

    public String generate(String intent, Map<String, String> modelOverride, String threadId) {
        return generate(intent, modelOverride, threadId, null);
    }

    public String generate(String intent, Map<String, String> modelOverride, String threadId, String genMsgId) {
        if (!enabled()) {
            stepUpdate(threadId, genMsgId, "generating", "生成技能代码", "error", "LLM 服务未初始化，无法生成 Skill");
            return null;
        }

        String existingSummary = existingSkillsSummary();
        String tmpSkillId = "new-" + UUID.randomUUID().toString().substring(0, 8);
        return generateViaLlm(intent, existingSummary, tmpSkillId, modelOverride, null, null, threadId, genMsgId);
    }

    public String refine(String skillId, String feedback) {
        return refine(skillId, feedback, null);
    }

    public String refine(String skillId, String feedback, Map<String, String> modelOverride) {
        return refine(skillId, feedback, modelOverride, null);
    }

    public String refine(String skillId, String feedback, Map<String, String> modelOverride, String threadId) {
        return refine(skillId, feedback, modelOverride, threadId, null);
    }

    public String refine(String skillId, String feedback, Map<String, String> modelOverride, String threadId, String genMsgId) {
        if (!enabled()) {
            stepUpdate(threadId, genMsgId, "generating", "优化技能代码", "error", "LLM 服务未初始化，无法优化 Skill");
            return null;
        }

        Path skillDir = Paths.get(skillsBaseDir, skillId);
        Path genomeDir = skillDir.resolve("genome");
        if (!Files.isDirectory(skillDir)) {
            stepUpdate(threadId, genMsgId, "generating", "优化技能代码", "error", "Skill 目录不存在: " + skillId);
            return null;
        }

        String skillJson, uiJson, handlersJs;
        try {
            skillJson = Files.readString(skillDir.resolve("skill.json"));
            uiJson = Files.readString(genomeDir.resolve("ui.json"));
            handlersJs = Files.readString(genomeDir.resolve("handlers.js"));
        } catch (Exception e) {
            stepUpdate(threadId, genMsgId, "generating", "优化技能代码", "error", "无法读取 Skill 文件: " + e.getMessage());
            return null;
        }

        return refineViaLlm(skillId, feedback, skillJson, uiJson, handlersJs, modelOverride, threadId, genMsgId);
    }

    // ── LLM path ────────────────────────────────────────────────

    private String generateViaLlm(String intent, String existingSummary, String skillId,
                                   Map<String, String> modelOverride,
                                   Map<String, Object> memoryCtx,
                                   Map<String, String> currentFiles,
                                   String threadId, String genMsgId) {

        Map<String, String> routing = storeService.getModelRouting("skill/generate");
        String modelDesc = modelOverride != null && modelOverride.containsKey("model")
            ? modelOverride.get("model")
            : routing.getOrDefault("model", "unknown");
        System.out.println("[kernel] [generate] routing: provider=" + routing.getOrDefault("provider","?") + " model=" + modelDesc + " apiUrl=" + routing.getOrDefault("apiUrl","?") + " hasApiKey=" + (!routing.getOrDefault("apiKey","").isEmpty()));

        stepUpdate(threadId, genMsgId, "generating", "生成技能代码", "in_progress",
            "正在生成 Skill（" + routing.getOrDefault("provider", "") + "/" + modelDesc + "）...");

        String userMessage = PromptBuilder.buildGenerate(intent,
            List.of(existingSummary.split("\n")), memoryCtx, currentFiles);

        try {
            Path workDir = Paths.get(skillsBaseDir, skillId + "-gen");
            SkillFileTools tools = new SkillFileTools(workDir, mapper, astAuditor);
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

            // Wire up file change notifications for frontend streaming log
            tools.setOnFileChange((action, path) -> {
                genLog(threadId, genMsgId, action, path, null);
                pushGenerationCard(threadId, genMsgId, null, false, null);
            });

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
                        System.out.println("[kernel] [generate] RAG found " + similar.size() + " similar skills");
                    }
                } catch (Exception e) {
                    log.warn("RAG retrieval failed: {}", e.getMessage());
                }
            }

            // ── Phase 1: PLAN ──
            genLog(threadId, genMsgId, "plan", "", "分析需求，制定计划...");
            PlannerAgent planner = agentFactory.createPlannerAgent(modelOverride);
            System.out.println("[kernel] [generate] Planner starting, provider=" + provider);
            stepUpdate(threadId, genMsgId, "generating", "生成技能代码", "in_progress", "制定计划中...");

            String planMessage = ragContext.isEmpty() ? userMessage : ragContext + "\n用户需求: " + userMessage;
            String planText = planner.plan(planMessage);
            System.out.println("[kernel] [generate] Plan: " + planText);
            Plan plan = parsePlan(planText);
            if (plan == null) {
                stepUpdate(threadId, genMsgId, "generating", "生成技能代码", "error", "计划解析失败");
                return null;
            }
            genLog(threadId, genMsgId, "plan", "", "计划: " + plan.files().size() + " 个文件");

            // ── Phase 2: EXECUTE ──
            // Create sandbox database for SQL seed steps
            Path seedDb = workDir.resolve("seed.db");
            java.sql.Connection seedConn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + seedDb.toString());
            seedConn.createStatement().execute("PRAGMA journal_mode=WAL");

            ChatModel chatModel = agentFactory.createChatModel(modelOverride);
            for (Plan.FileSpec file : plan.files()) {
                genLog(threadId, genMsgId, "exec", file.path(), file.purpose());
                stepUpdate(threadId, genMsgId, "generating", "生成技能代码", "in_progress",
                    "生成 " + file.path());

                if (file.path().startsWith("sql:")) {
                    // ── SQL seed: execute directly against sandbox DB ──
                    String dbPrompt = buildDbSeedPrompt(file, plan, intent);
                    ChatResponse resp = chatModel.chat(ChatRequest.builder()
                        .messages(SystemMessage.from(dbPrompt), UserMessage.from("输出SQL，每条以;结尾，不要解释"))
                        .build());
                    String sql = resp.aiMessage().text();
                    if (sql != null && !sql.isBlank()) {
                        sql = stripMarkdownFences(sql);
                        int count = 0;
                        for (String stmt : sql.split(";")) {
                            stmt = stmt.trim();
                            if (!stmt.isEmpty() && !stmt.startsWith("--")) {
                                try { seedConn.createStatement().execute(stmt); count++; } catch (Exception e) {
                                    System.out.println("[kernel] [generate] SQL skip: " + e.getMessage());
                                }
                            }
                        }
                        genLog(threadId, genMsgId, "sql", "", count + " 条SQL已执行");
                    }
                } else {
                    // ── File generation: any path, any format ──
                    String filePrompt = buildFilePrompt(file, plan, intent);
                    ChatResponse resp = chatModel.chat(ChatRequest.builder()
                        .messages(SystemMessage.from(filePrompt), UserMessage.from("输出 " + file.path() + " 的内容"))
                        .build());
                    String content = resp.aiMessage().text();
                    if (content != null && !content.isBlank()) {
                        content = stripMarkdownFences(content);
                        tools.createFile(file.path(), content);
                    } else {
                        System.out.println("[kernel] [generate] WARNING: empty response for " + file.path());
                    }
                }
            }
            seedConn.close();

            // Read files from working directory
            Map<String, String> allFiles = tools.readAllFiles();
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

            String resultId = auditAndInstall(normalizer.normalize(files), skillId, modelOverride,
                intent, "Initial Design", designDecision, threadId, genMsgId);

            // Copy seed data from staging to installed skill
            if (resultId != null) {
                copySeedData(workDir, Paths.get(skillsBaseDir, resultId));
            }
            return resultId;

        } catch (Exception e) {
            System.out.println("[kernel] [generate] EXCEPTION: " + e.getClass().getName() + ": " + (e.getMessage() != null ? e.getMessage() : "(null)"));
            e.printStackTrace(System.out);
            log.error("LLM generation failed", e);
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) {
                Throwable cause = e.getCause();
                msg = e.getClass().getSimpleName()
                    + (cause != null ? " caused by " + cause.getClass().getSimpleName() + ": " + cause.getMessage() : "");
            }
            eventBus.publish(new KernelEvent.PlanStep("error", "LLM 生成失败: " + msg, threadId));
            stepUpdate(threadId, genMsgId, "generating", "生成技能代码", "error", "LLM 调用失败: " + msg);
            return null;
        } finally {
            // Clean up gen staging directory — prevents stale dirs from overwriting
            // real skill A2UI trees on next startup (they share the manifest id).
            Path stagingDir = Paths.get(skillsBaseDir, skillId + "-gen");
            cleanupDir(stagingDir);
            System.out.println("[kernel] [generate] Cleaned up staging dir: " + stagingDir.getFileName());
        }
    }

    private String refineViaLlm(String skillId, String feedback,
                                 String skillJson, String uiJson, String handlersJs,
                                 Map<String, String> modelOverride, String threadId,
                                 String genMsgId) {

        Map<String, String> routing = storeService.getModelRouting("skill/generate");
        String modelDesc = modelOverride != null && modelOverride.containsKey("model")
            ? modelOverride.get("model")
            : routing.getOrDefault("model", "unknown");

        stepUpdate(threadId, genMsgId, "generating", "优化技能代码", "in_progress",
            "正在优化 Skill " + skillId + "（" + routing.getOrDefault("provider", "") + "/" + modelDesc + "）...");

        Map<String, Object> memoryCtx = memoryService.getMemoryContext(skillId);

        try {
            // ── ReAct refine: seed working dir with existing files, let LLM update via tools ──
            Path workDir = Paths.get(skillsBaseDir, skillId + "-refine");
            SkillFileTools tools = new SkillFileTools(workDir);
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
            PlannerAgent planner = agentFactory.createPlannerAgent(modelOverride);
            String refinePrompt = "Optimize existing skill " + skillId + " based on feedback: " + feedback;
            String planText = planner.plan(refinePrompt);
            Plan plan = parsePlan(planText);

            if (plan == null || plan.files().isEmpty()) {
                return auditAndInstall(normalizer.normalize(
                    new SkillFiles(skillJson, uiJson, handlersJs)), skillId, modelOverride,
                    null, "Refine", null, threadId, genMsgId);
            }

            // Execute: regenerate each file listed in the plan
            ChatModel chatModel = agentFactory.createChatModel(modelOverride);
            for (Plan.FileSpec file : plan.files()) {
                stepUpdate(threadId, genMsgId, "generating", "优化技能代码", "in_progress",
                    "优化 " + file.path());
                String filePrompt = "优化以下文件，根据反馈: " + feedback + "\n\n"
                    + "当前内容:\n" + (file.path().contains("ui") ? uiJson : file.path().contains("handler") ? handlersJs : skillJson);
                ChatResponse resp = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(filePrompt), UserMessage.from("输出 " + file.path() + " 的新内容"))
                    .build());
                String content = resp.aiMessage().text();
                if (content != null && !content.isBlank()) {
                    tools.updateFile(file.path(), stripMarkdownFences(content));
                }
            }

            Map<String, String> allFiles = tools.readAllFiles();
            SkillFiles rawFiles = buildSkillFiles(allFiles, skillId);
            SkillFiles rawFixed = new SkillFiles(
                forceSkillId(rawFiles.skillJson(), skillId),
                rawFiles.uiJson(), rawFiles.handlersJs(),
                rawFiles.handlerModules(), rawFiles.uiFragments()
            );

            SkillFiles normalized = normalizer.normalize(rawFixed);
            String designTitle = "Refine: " + feedback.substring(0, Math.min(60, feedback.length()));

            return auditAndInstall(normalized, skillId, modelOverride,
                null, designTitle, null, threadId, genMsgId);

        } catch (Exception e) {
            log.error("LLM refine failed for {}", skillId, e);
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) {
                Throwable cause = e.getCause();
                msg = e.getClass().getSimpleName()
                    + (cause != null ? " caused by " + cause.getClass().getSimpleName() + ": " + cause.getMessage() : "");
            }
            eventBus.publish(new KernelEvent.PlanStep("error", "LLM 优化失败: " + msg, threadId));
            stepUpdate(threadId, genMsgId, "generating", "优化技能代码", "error", "LLM 优化失败: " + msg);
            return null;
        } finally {
            Path stagingDir = Paths.get(skillsBaseDir, skillId + "-refine");
            cleanupDir(stagingDir);
            System.out.println("[kernel] [refine] Cleaned up staging dir: " + stagingDir.getFileName());
        }
    }

    private SkillFiles llmRepair(SkillFiles current, String skillId, String error,
                                  int attempt, Map<String, String> modelOverride) {
        SkillRepairAgent agent = agentFactory.createRepairAgent(skillId, modelOverride);
        String originalIntent = memoryService.readIntent(skillId);
        String userMessage = PromptBuilder.buildRepair(error, current, originalIntent, attempt);

        try {
            String raw = agent.repair(userMessage, skillId);
            return OutputParser.parseThreeFiles(raw);
        } catch (Exception e) {
            log.error("Repair call failed for {}", skillId, e);
            throw new RuntimeException("LLM 修复失败: " + e.getMessage(), e);
        }
    }

    // ── Audit + Repair loop + Install ───────────────────────────

    private String auditAndInstall(SkillFiles initialFiles, String skillId,
                                    Map<String, String> modelOverride,
                                    String intent, String designTitle, String designDecision,
                                    String threadId, String genMsgId) {
        stepUpdate(threadId, genMsgId, "normalizing", "规范化输出", "in_progress", "正在规范化输出...");
        SkillFiles finalFiles = initialFiles;

        for (int attempt = 0; attempt <= maxRepairAttempts; attempt++) {
            try {
                SkillManifest manifest;
                try {
                    manifest = mapper.readValue(finalFiles.skillJson(), SkillManifest.class);
                } catch (Exception e) {
                    throw new GateException("INVALID_MANIFEST", "skill.json 解析失败: " + e.getMessage());
                }

                SkillGenome genome = new SkillGenome(finalFiles.uiJson(), finalFiles.handlersJs(), finalFiles.handlerModules());

                stepUpdate(threadId, genMsgId, "auditing", "安全审计", "in_progress", "正在审计 Skill " + manifest.id() + "...");

                // DEBUG: find all lines containing "0x" to see raw hex color values
                String js = finalFiles.handlersJs();
                if (js != null) {
                    String[] lines = js.split("\n");
                    for (int li = 0; li < lines.length; li++) {
                        if (lines[li].contains("0x")) {
                            System.out.println("[audit-debug] attempt=" + attempt + " line" + (li+1) + " 0x found: " + lines[li].trim());
                        }
                    }
                }

                astAuditor.audit(manifest, genome);
                guardian.check(manifest.id(), genome);

                // Drift check on refine (not first generation)
                String currentSkillId = manifest.id();
                String originalIntent = memoryService.readIntent(currentSkillId);
                if (originalIntent != null && !originalIntent.isBlank()) {
                    runDriftCheck(currentSkillId, originalIntent, finalFiles.handlersJs(),
                        "audit-passed", modelOverride);
                }

                String installedId = installSkill(manifest, finalFiles, threadId, genMsgId);
                if (installedId != null) {
                    memoryExecutor.submit(() -> persistMemory(installedId, intent, designTitle, designDecision));
                }
                return installedId;

            } catch (GateException e) {
                String errorDetail = "[" + e.getCode() + "] " + e.getMessage();
                log.warn("Audit failed (attempt {}): {}", attempt + 1, errorDetail);
                System.out.println("[audit] FAIL attempt=" + (attempt + 1) + " code=" + e.getCode() + " msg=" + e.getMessage());

                String realSkillId = extractSkillId(finalFiles.skillJson());
                eventBus.publish(new KernelEvent.RepairAttempt(realSkillId, attempt + 1, errorDetail));

                if (attempt >= maxRepairAttempts) {
                    stepUpdate(threadId, genMsgId, "repairing", "自动修复", "error",
                        "修复已耗尽（" + maxRepairAttempts + " 次），最后的错误: " + errorDetail);
                    return null;
                }

                stepUpdate(threadId, genMsgId, "repairing", "自动修复", "in_progress",
                    "第 " + (attempt + 1) + "/" + maxRepairAttempts + " 次修复中: " + e.getCode());

                try {
                    SkillFiles repaired = llmRepair(finalFiles, realSkillId, errorDetail, attempt + 1, modelOverride);
                    SkillFiles repairedFixed = new SkillFiles(
                        forceSkillId(repaired.skillJson(), realSkillId),
                        repaired.uiJson(),
                        repaired.handlersJs()
                    );
                    finalFiles = normalizer.normalize(repairedFixed);
                } catch (Exception repairEx) {
                    log.error("Repair call failed", repairEx);
                    stepUpdate(threadId, genMsgId, "repairing", "自动修复", "error", "修复调用失败: " + repairEx.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    // ── Drift check ─────────────────────────────────────────────

    private void runDriftCheck(String skillId, String originalIntent,
                                String currentHandlersJs, String proposedChange,
                                Map<String, String> modelOverride) {
        try {
            DriftAnalysisAgent driftAgent = agentFactory.createDriftAgent(skillId, modelOverride);
            String userMessage = PromptBuilder.buildDriftAnalysis(
                originalIntent, List.of(), currentHandlersJs, proposedChange);
            DriftResult result = driftAgent.analyze(userMessage, skillId);
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
                    "Skill " + skillId + " 的修改偏离了原始意图（评分 " +
                    String.format("%.1f", result.driftScore()) + "/10）"));
            }
        } catch (Exception e) {
            log.warn("Drift check failed for {}: {}", skillId, e.getMessage());
        }
    }

    // ── Memory persistence ───────────────────────────────────────

    private void persistMemory(String skillId, String intent, String designTitle, String designDecision) {
        if (intent != null) {
            try {
                memoryService.writeIntent(skillId, intent);
            } catch (IOException e) {
                log.debug("Intent already locked for {}: {}", skillId, e.getMessage());
            }
        }
        // Always record design entry: use LLM-provided reasoning if present, else fall back to title
        String entry = designDecision != null && !designDecision.isBlank()
            ? designDecision
            : (designTitle != null ? designTitle : "Design Decision");
        String title = designTitle != null ? designTitle : "Design Decision";
        memoryService.appendDesign(skillId, title, entry);
    }

    // ── Install generated skill ─────────────────────────────────

    private String installSkill(SkillManifest manifest, SkillFiles files, String threadId, String genMsgId) {
        String skillId = manifest.id();

        stepUpdate(threadId, genMsgId, "installing", "安装部署", "in_progress", "正在安装 Skill: " + skillId + "...");

        try {
            Path skillDir = Paths.get(skillsBaseDir, skillId);
            Path genomeDir = skillDir.resolve("genome");

            // Clean stale artifacts but preserve memory/ (immutable intent + design log)
            if (Files.exists(skillDir)) {
                // Delete individual files/dirs, not memory/
                for (String name : List.of("skill.json", "genome", "phenotype")) {
                    Path p = skillDir.resolve(name);
                    if (Files.isDirectory(p)) {
                        try (Stream<Path> walk = Files.walk(p)) {
                            walk.sorted(Comparator.reverseOrder())
                                .forEach(x -> { try { Files.delete(x); } catch (Exception ignored) {} });
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

            // ── Write UI files: multi-fragment or single-file ──
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
                // index.json is the main UI entry; it's already part of uiFragments
            } else {
                String prettyUiJson = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(mapper.readTree(files.uiJson()));
                Files.writeString(genomeDir.resolve("ui.json"), prettyUiJson);
            }

            // ── Write handler files: multi-module or single-file ──
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
                stepUpdate(threadId, genMsgId, "installing", "安装部署", "error", "Skill 加载失败: " + e.getMessage());
                return null;
            }

            // Mark all steps as done before activation
            for (String key : List.of("generating", "normalizing", "auditing", "repairing", "installing")) {
                stepUpdate(threadId, genMsgId, key, stepLabel(key), "done", "Skill " + skillId + " 已生成并激活");
            }

            // Push final generation card with skillId + allDone
            if (onMessageUpdate != null && genMsgId != null) {
                // Add done log entry directly (avoid genLog which pushes allDone=false)
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

            // Final push to guarantee card shows as done (overwrite any late genLog pushes)
            if (onMessageUpdate != null && genMsgId != null) {
                pushGenerationCard(threadId, genMsgId, skillId, true, null);
            }
            return skillId;

        } catch (Exception e) {
            log.error("Failed to install generated skill {}: {}", skillId, e.getMessage());
            stepUpdate(threadId, genMsgId, "installing", "安装部署", "error", "安装失败: " + e.getMessage());
            return null;
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

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
        try {
            // Strip any markdown fences or surrounding text
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
                        f.has("purpose") ? f.get("purpose").asText() : ""
                    ));
                }
            }
            return new Plan(skillId, runtime, capabilities, files);
        } catch (Exception e) {
            System.out.println("[kernel] [generate] Failed to parse plan: " + e.getMessage());
            return null;
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
                - 精确7个顶级字段: id, version, abi, runtime, meta, capabilities, signature（多一个少一个都会失败）
                - id: kebab-case, version: X.Y.Z, abi: "1.0"
                - capabilities: JSON对象{}不是数组[], 只声明handlers.js实际使用的能力, 每个能力用空对象{}如"database":{}
                - signature: 必须是 {"publisher":"local"}
                - runtime: js=browser-js""");
        } else if (file.path().contains("ui")) {
            sb.append("""
                输出 ui/index.json 的完整内容。A2UI组件树格式:
                - 顶层 {"components": [...]} 数组
                - root组件必须是 id="root" component="Column"
                - Column/Row的children是id字符串数组, Button的child是单个id字符串
                - Button的action.event.name必须等于Button的id
                - label Text组件不能放在任何container的children里
                - 所有children/child引用的id必须存在
                - Text: {"id":"x","component":"Text","text":"内容","variant":"h1|h2|h3|h4|h5|body|caption"}
                - Accordion items: [{value,triggerText,contentId}]""");
        } else if (file.path().contains("handler")) {
            sb.append("""
                输出 handlers/index.js 的完整内容。规则:
                - 顶层函数自动注册, 禁止 module.exports
                - JS运行时: 禁止 async/await/Promise/eval/Function/fetch/WebSocket
                - 函数签名: function onXxx(__inputs__, capability)
                - Button "my-btn" -> function onMyBtn
                - 用capability.ui.set(id,"text",value)更新Text文字
                - 从__inputs__.fieldId读取TextField输入值
                - capability.storage.get/put/delete 做KV持久化
                - capability.db.run(sql) DDL, capability.db.query(sql,[params]) SELECT, capability.db.execute(sql,[params]) INSERT/UPDATE/DELETE
                - db params可以是数字或字符串: query("SELECT..OFFSET ?", [0]) √ query("SELECT..OFFSET ?", ["0"]) √
                - 如果声明了database: onInit里先用run()建表, 用execute()插数据, 在按钮handler里用query()查数据""");
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
            .replaceFirst("^```(?:json|js)?\\s*\\n?", "")
            .replaceFirst("\\n?```\\s*$", "")
            .trim();
    }

    // In-memory aggregation of generation steps keyed by genMsgId
    private final ConcurrentHashMap<String, Map<String, Map<String, String>>> generationSteps = new ConcurrentHashMap<>();
    // Streaming log entries for frontend GenerationCard
    private final ConcurrentHashMap<String, List<Map<String, String>>> generationLogs = new ConcurrentHashMap<>();

    /** Append a streaming log entry (does NOT push card by itself). */
    private void genLog(String threadId, String genMsgId, String action, String path, String detail) {
        if (threadId == null || genMsgId == null || onMessageUpdate == null) return;
        generationLogs.computeIfAbsent(genMsgId, k -> new ArrayList<>());
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("time", timeStr());
        entry.put("action", action);
        entry.put("path", path != null ? path : "");
        entry.put("detail", detail != null ? detail : "");
        generationLogs.get(genMsgId).add(entry);
    }

    /** Push current generation card state to frontend. */
    private void pushGenerationCard(String threadId, String genMsgId,
                                     String skillId, boolean allDone, String error) {
        if (onMessageUpdate == null) return;
        Map<String, Map<String, String>> genSteps = generationSteps.get(genMsgId);
        List<Map<String, String>> stepsList = new ArrayList<>();
        if (genSteps != null) {
            for (String k : new String[]{"generating", "normalizing", "auditing", "repairing", "installing"}) {
                if (genSteps.containsKey(k)) stepsList.add(genSteps.get(k));
            }
        }
        List<Map<String, String>> logs = generationLogs.getOrDefault(genMsgId, List.of());
        // Keep last 50 entries
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

    /** Write a phase step update + push card. */
    private void stepUpdate(String threadId, String genMsgId, String key, String label, String status, String desc) {
        if (threadId == null || threadId.isEmpty() || genMsgId == null) return;
        if (onMessageUpdate != null) {
            generationSteps.computeIfAbsent(genMsgId, k -> new LinkedHashMap<>());
            Map<String, String> step = new LinkedHashMap<>();
            step.put("key", key);
            step.put("label", label);
            step.put("status", status);
            generationSteps.get(genMsgId).put(key, step);

            boolean allDone = generationSteps.get(genMsgId).values().stream().allMatch(s -> "done".equals(s.get("status")));
            boolean hasError = generationSteps.get(genMsgId).values().stream().anyMatch(s -> "error".equals(s.get("status")));
            pushGenerationCard(threadId, genMsgId, null, allDone, hasError ? desc : null);
        }
        eventBus.publish(new KernelEvent.PlanStep(status, desc, threadId));
    }

    /**
     * Convert flat file map from ReAct working directory into a SkillFiles record.
     * Detects single-file vs multi-file layout automatically.
     */
    private SkillFiles buildSkillFiles(Map<String, String> allFiles, String skillId) {
        String skillJson = allFiles.getOrDefault("skill.json", "");
        if (skillJson.isEmpty()) {
            skillJson = "{ \"id\": \"" + skillId.replace("new-", "skill-") + "\", \"version\": \"1.0.0\", \"abi\": \"1.0\", \"runtime\": \"js\", \"meta\": {}, \"capabilities\": {}, \"signature\": { \"publisher\": \"local\" } }";
        }
        // If agent wrote temp "new-xxx" id, replace with stable "skill-xxx"
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

        return new SkillFiles(skillJson, uiJson, handlersJs, handlerModules, uiFragments);
    }

    /**
     * If the ReAct model didn't create all required files, fill in minimal valid stubs.
     * This lets the audit→repair loop fix them instead of failing outright.
     */
    private SkillFiles ensureRequiredFiles(SkillFiles files, String skillId, String intent) {
        String uiJson = files.uiJson();
        String handlersJs = files.handlersJs();
        boolean patched = false;

        // Use the real skill id from skill.json if available, not the temp UUID
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
            System.out.println("[kernel] [generate] Safety net: filled missing files for " + skillId);
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

    private List<String> buildFileProgress(SkillFiles files) {
        List<String> progress = new ArrayList<>();
        progress.add("skill.json ✓");
        if (files.handlerModules() != null && !files.handlerModules().isEmpty()) {
            for (String path : files.handlerModules().keySet()) {
                progress.add(path + " ✓");
            }
        } else if (files.handlersJs() != null && !files.handlersJs().isBlank()) {
            progress.add("handlers.js ✓");
        }
        if (files.uiFragments() != null && !files.uiFragments().isEmpty()) {
            for (String path : files.uiFragments().keySet()) {
                progress.add(path + " ✓");
            }
        } else if (files.uiJson() != null && !files.uiJson().isBlank()) {
            progress.add("ui.json ✓");
        }
        return progress;
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
                        Files.copy(f, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[kernel] [generate] Seed data copy failed: " + e.getMessage());
        }
    }

    /** Recursively delete a directory. */
    private void cleanupDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                }
            }
        } catch (Exception ignored) {}
    }

    /** Copy docs/ resources from classpath to workDir so the agent can readFile them. */
    private void seedDocs(Path workDir) {
        try {
            Path docsDir = workDir.resolve("docs");
            Files.createDirectories(docsDir);

            // Only seed essential docs — too many docs encourages the agent to read them all
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
                    Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    is.close();
                } else {
                    System.out.println("[kernel] [generate] WARNING: doc resource not found: " + resourcePath);
                }
            }
            System.out.println("[kernel] [generate] Seeded docs/ into " + docsDir);
        } catch (Exception e) {
            System.out.println("[kernel] [generate] Failed to seed docs: " + e.getMessage());
            // Non-fatal — agent can still work without docs
        }
    }
}
