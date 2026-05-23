package com.wuwei.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.bus.EventBus;
import com.wuwei.bus.event.KernelEvent;
import com.wuwei.gate.AstAuditor;
import com.wuwei.gate.EcosystemGuardian;
import com.wuwei.gate.GateException;
import com.wuwei.skill.SkillGenome;
import com.wuwei.skill.SkillManager;
import com.wuwei.skill.SkillManifest;
import com.wuwei.snapshot.SnapshotService;
import com.wuwei.store.SkillMemoryService;
import com.wuwei.store.StoreService;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    private final SnapshotService snapshotService;
    private final EventBus eventBus;
    private final ObjectMapper mapper;
    private final String skillsBaseDir;
    private final int maxRepairAttempts;
    private final ExecutorService memoryExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public SkillGenerator(AgentFactory agentFactory,
                          SkillMemoryService memoryService, StoreService storeService,
                          Normalizer normalizer,
                          AstAuditor astAuditor, EcosystemGuardian guardian,
                          SkillManager skillManager, SnapshotService snapshotService,
                          EventBus eventBus, ObjectMapper mapper,
                          int maxRepairAttempts) {
        this.agentFactory = agentFactory;
        this.memoryService = memoryService;
        this.storeService = storeService;
        this.normalizer = normalizer;
        this.astAuditor = astAuditor;
        this.guardian = guardian;
        this.skillManager = skillManager;
        this.snapshotService = snapshotService;
        this.eventBus = eventBus;
        this.mapper = mapper;
        this.maxRepairAttempts = maxRepairAttempts;

        String home = System.getProperty("user.home");
        this.skillsBaseDir = Paths.get(home, ".wuwei", "skills").toString();
    }

    // ── Public API ──────────────────────────────────────────────

    public boolean enabled() {
        return agentFactory != null;
    }

    public String generate(String intent) {
        return generate(intent, null);
    }

    public String generate(String intent, Map<String, String> modelOverride) {
        if (!enabled()) {
            eventBus.publish(new KernelEvent.PlanStep("error", "LLM 服务未初始化，无法生成 Skill"));
            return null;
        }

        String existingSummary = existingSkillsSummary();
        String tmpSkillId = "new-" + UUID.randomUUID().toString().substring(0, 8);
        return generateViaLlm(intent, existingSummary, tmpSkillId, modelOverride, null, null);
    }

    public String refine(String skillId, String feedback) {
        return refine(skillId, feedback, null);
    }

    public String refine(String skillId, String feedback, Map<String, String> modelOverride) {
        if (!enabled()) {
            eventBus.publish(new KernelEvent.PlanStep("error", "LLM 服务未初始化，无法优化 Skill"));
            return null;
        }

        Path skillDir = Paths.get(skillsBaseDir, skillId);
        Path genomeDir = skillDir.resolve("genome");
        if (!Files.isDirectory(skillDir)) {
            eventBus.publish(new KernelEvent.PlanStep("error", "Skill 目录不存在: " + skillId));
            return null;
        }

        String skillJson, uiJson, handlersJs;
        try {
            skillJson = Files.readString(skillDir.resolve("skill.json"));
            uiJson = Files.readString(genomeDir.resolve("ui.json"));
            handlersJs = Files.readString(genomeDir.resolve("handlers.js"));
        } catch (Exception e) {
            eventBus.publish(new KernelEvent.PlanStep("error", "无法读取 Skill 文件: " + e.getMessage()));
            return null;
        }

        return refineViaLlm(skillId, feedback, skillJson, uiJson, handlersJs, modelOverride);
    }

    // ── LLM path ────────────────────────────────────────────────

    private String generateViaLlm(String intent, String existingSummary, String skillId,
                                   Map<String, String> modelOverride,
                                   Map<String, Object> memoryCtx,
                                   Map<String, String> currentFiles) {

        SkillGenerateAgent agent = agentFactory.createGenerateAgent(skillId, modelOverride);
        Map<String, String> routing = storeService.getModelRouting("skill/generate");
        String modelDesc = modelOverride != null && modelOverride.containsKey("model")
            ? modelOverride.get("model")
            : routing.getOrDefault("model", "unknown");

        eventBus.publish(new KernelEvent.PlanStep("generating",
            "正在生成 Skill（" + routing.getOrDefault("provider", "") + "/" + modelDesc + "）..."));

        String userMessage = PromptBuilder.buildGenerate(intent,
            List.of(existingSummary.split("\n")), memoryCtx, currentFiles);

        try {
            StringBuilder acc = new StringBuilder();
            CompletableFuture<String> done = new CompletableFuture<>();

            log.info("Starting LLM generate call (model={})", modelDesc);
            TokenStream stream = agent.generate(userMessage, skillId);
            log.info("TokenStream obtained, registering callbacks");

            stream
                .onPartialResponse(partial -> {
                    acc.append(partial);
                    String text = acc.toString();
                    if (text.contains("=== handlers.js ===")) {
                        eventBus.publish(new KernelEvent.PlanStep("generating", "正在生成处理逻辑..."));
                    }
                })
                .onCompleteResponse((ChatResponse response) -> {
                    String text = response.aiMessage() != null ? response.aiMessage().text() : "";
                    done.complete(text != null ? text : "");
                })
                .onError(done::completeExceptionally)
                .start();
            log.info("Stream started, waiting for completion");

            String raw = done.get(360, TimeUnit.SECONDS);

            String preview = raw.length() > 600 ? raw.substring(0, 600) + "..." : raw;
            log.info("LLM raw response preview ({} chars total):\n{}", raw.length(), preview);

            SkillFiles files = OutputParser.parseThreeFiles(raw);

            String designDecision = OutputParser.extractDesignDecision(raw);

            eventBus.publish(new KernelEvent.PlanStep("generating", "生成完成"));
            return auditAndInstall(normalizer.normalize(files), skillId, modelOverride,
                intent, "Initial Design", designDecision);

        } catch (Exception e) {
            log.error("LLM generation failed", e);
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) {
                Throwable cause = e.getCause();
                msg = e.getClass().getSimpleName()
                    + (cause != null ? " caused by " + cause.getClass().getSimpleName() + ": " + cause.getMessage() : "");
            }
            eventBus.publish(new KernelEvent.PlanStep("error", "LLM 生成失败: " + msg));
            return null;
        }
    }

    private String refineViaLlm(String skillId, String feedback,
                                 String skillJson, String uiJson, String handlersJs,
                                 Map<String, String> modelOverride) {

        SkillGenerateAgent agent = agentFactory.createGenerateAgent(skillId, modelOverride);
        Map<String, String> routing = storeService.getModelRouting("skill/generate");
        String modelDesc = modelOverride != null && modelOverride.containsKey("model")
            ? modelOverride.get("model")
            : routing.getOrDefault("model", "unknown");

        eventBus.publish(new KernelEvent.PlanStep("generating",
            "正在优化 Skill " + skillId + "（" + routing.getOrDefault("provider", "") + "/" + modelDesc + "）..."));

        // ChatMemory auto-loads conversation history via @MemoryId — no manual evolution reading
        Map<String, Object> memoryCtx = memoryService.getMemoryContext(skillId);

        Map<String, String> currentFiles = Map.of(
            "skillJson", skillJson, "uiJson", uiJson, "handlersJs", handlersJs);

        String userMessage = PromptBuilder.buildGenerate(
            "Refine skill based on feedback: " + feedback,
            List.of(), memoryCtx, currentFiles);

        try {
            StringBuilder acc = new StringBuilder();
            CompletableFuture<String> done = new CompletableFuture<>();

            agent.generate(userMessage, skillId)
                .onPartialResponse(acc::append)
                .onCompleteResponse((ChatResponse response) -> {
                    String text = response.aiMessage() != null ? response.aiMessage().text() : "";
                    done.complete(text != null ? text : "");
                })
                .onError(done::completeExceptionally)
                .start();

            String raw = done.get(360, TimeUnit.SECONDS);

            SkillFiles rawFiles = OutputParser.parseThreeFiles(raw);
            SkillFiles rawFixed = new SkillFiles(
                forceSkillId(rawFiles.skillJson(), skillId),
                rawFiles.uiJson(),
                rawFiles.handlersJs()
            );
            SkillFiles normalized = normalizer.normalize(rawFixed);

            String designDecision = OutputParser.extractDesignDecision(raw);
            String designTitle = "Refine: " + feedback.substring(0, Math.min(60, feedback.length()));

            return auditAndInstall(normalized, skillId, modelOverride,
                null, designTitle, designDecision);

        } catch (Exception e) {
            log.error("LLM refine failed for {}", skillId, e);
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) {
                Throwable cause = e.getCause();
                msg = e.getClass().getSimpleName()
                    + (cause != null ? " caused by " + cause.getClass().getSimpleName() + ": " + cause.getMessage() : "");
            }
            eventBus.publish(new KernelEvent.PlanStep("error", "LLM 优化失败: " + msg));
            return null;
        }
    }

    private SkillFiles llmRepair(SkillFiles current, String skillId, String error,
                                  int attempt, Map<String, String> modelOverride) {
        SkillRepairAgent agent = agentFactory.createRepairAgent(skillId, modelOverride);
        String originalIntent = memoryService.readIntent(skillId);
        String userMessage = PromptBuilder.buildRepair(error, current, originalIntent, attempt);

        try {
            StringBuilder acc = new StringBuilder();
            CompletableFuture<String> done = new CompletableFuture<>();

            agent.repair(userMessage, skillId)
                .onPartialResponse(acc::append)
                .onCompleteResponse((ChatResponse response) -> {
                    String text = response.aiMessage() != null ? response.aiMessage().text() : "";
                    done.complete(text != null ? text : "");
                })
                .onError(done::completeExceptionally)
                .start();

            String raw = done.get(360, TimeUnit.SECONDS);
            return OutputParser.parseThreeFiles(raw);

        } catch (Exception e) {
            log.error("Repair call failed for {}", skillId, e);
            throw new RuntimeException("LLM 修复失败: " + e.getMessage(), e);
        }
    }

    // ── Audit + Repair loop + Install ───────────────────────────

    private String auditAndInstall(SkillFiles initialFiles, String skillId,
                                    Map<String, String> modelOverride,
                                    String intent, String designTitle, String designDecision) {
        eventBus.publish(new KernelEvent.PlanStep("normalizing", "正在规范化输出..."));
        SkillFiles finalFiles = initialFiles;

        for (int attempt = 0; attempt <= maxRepairAttempts; attempt++) {
            try {
                SkillManifest manifest;
                try {
                    manifest = mapper.readValue(finalFiles.skillJson(), SkillManifest.class);
                } catch (Exception e) {
                    throw new GateException("INVALID_MANIFEST", "skill.json 解析失败: " + e.getMessage());
                }

                SkillGenome genome = new SkillGenome(finalFiles.uiJson(), finalFiles.handlersJs());

                eventBus.publish(new KernelEvent.PlanStep("auditing",
                    "正在审计 Skill " + manifest.id() + "..."));

                astAuditor.audit(manifest, genome);
                guardian.check(manifest.id(), genome);

                // Drift check on refine (not first generation)
                String currentSkillId = manifest.id();
                String originalIntent = memoryService.readIntent(currentSkillId);
                if (originalIntent != null && !originalIntent.isBlank()) {
                    runDriftCheck(currentSkillId, originalIntent, finalFiles.handlersJs(),
                        "audit-passed", modelOverride);
                }

                String installedId = installSkill(manifest, finalFiles);
                if (installedId != null) {
                    memoryExecutor.submit(() -> persistMemory(installedId, intent, designTitle, designDecision));
                }
                return installedId;

            } catch (GateException e) {
                String errorDetail = "[" + e.getCode() + "] " + e.getMessage();
                log.warn("Audit failed (attempt {}): {}", attempt + 1, errorDetail);

                String realSkillId = extractSkillId(finalFiles.skillJson());
                eventBus.publish(new KernelEvent.RepairAttempt(realSkillId, attempt + 1, errorDetail));

                if (attempt >= maxRepairAttempts) {
                    eventBus.publish(new KernelEvent.PlanStep("error",
                        "修复已耗尽（" + maxRepairAttempts + " 次），最后的错误: " + errorDetail));
                    return null;
                }

                eventBus.publish(new KernelEvent.PlanStep("repairing",
                    "第 " + (attempt + 1) + "/" + maxRepairAttempts + " 次修复中: " + e.getCode()));

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
                    eventBus.publish(new KernelEvent.PlanStep("error", "修复调用失败: " + repairEx.getMessage()));
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

    private String installSkill(SkillManifest manifest, SkillFiles files) {
        String skillId = manifest.id();

        eventBus.publish(new KernelEvent.PlanStep("installing",
            "正在安装 Skill: " + skillId + "..."));

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

            String prettyUiJson = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(mapper.readTree(files.uiJson()));
            Files.writeString(genomeDir.resolve("ui.json"), prettyUiJson);

            Files.writeString(genomeDir.resolve("handlers.js"), files.handlersJs());

            log.info("Generated skill files written to {}", skillDir);

            snapshotService.delete(skillId);

            eventBus.publish(new KernelEvent.SkillLoading(skillId));

            try {
                skillManager.loadFromDirectory(skillDir);
            } catch (Exception e) {
                log.error("Failed to load generated skill {}: {}", skillId, e.getMessage());
                eventBus.publish(new KernelEvent.PlanStep("error", "Skill 加载失败: " + e.getMessage()));
                return null;
            }

            skillManager.activate(skillId);

            eventBus.publish(new KernelEvent.PlanStep("done", "Skill " + skillId + " 已生成并激活"));
            return skillId;

        } catch (Exception e) {
            log.error("Failed to install generated skill {}: {}", skillId, e.getMessage());
            eventBus.publish(new KernelEvent.PlanStep("error", "安装失败: " + e.getMessage()));
            return null;
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

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
}
