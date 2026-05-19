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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Full Skill generation pipeline:
 * intent → PI (or LLM fallback) → parse → normalize → audit → repair loop → save → load → activate.
 *
 * When PiMonoAdapter is available, uses the PI framework for LLM calls with
 * model routing from StoreService and memory persistence via SkillMemoryService.
 * Falls back to built-in LlmClient when PI is not running.
 */
public class SkillGenerator {

    private static final Logger log = LoggerFactory.getLogger(SkillGenerator.class);

    private final LlmClient llmClient;
    private final PiMonoAdapter piAdapter;
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

    private final String generatePrompt;
    private final String repairPrompt;
    private final String refinePrompt;

    public SkillGenerator(LlmClient llmClient, PiMonoAdapter piAdapter,
                          SkillMemoryService memoryService, StoreService storeService,
                          Normalizer normalizer,
                          AstAuditor astAuditor, EcosystemGuardian guardian,
                          SkillManager skillManager, SnapshotService snapshotService,
                          EventBus eventBus, ObjectMapper mapper,
                          int maxRepairAttempts) {
        this.llmClient = llmClient;
        this.piAdapter = piAdapter;
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

        this.generatePrompt = loadResource("generate.txt");
        this.repairPrompt = loadResource("repair.txt");
        this.refinePrompt = loadResource("refine.txt");
    }

    // ── Public API ──────────────────────────────────────────────

    public boolean enabled() {
        return piAdapter != null || llmClient.config().enabled();
    }

    public String generate(String intent) {
        if (!enabled()) {
            eventBus.publish(new KernelEvent.PlanStep("error",
                "LLM 未配置。请设置 OPENAI_API_KEY 环境变量或启动 PI 进程"));
            return null;
        }

        String existingSummary = existingSkillsSummary();
        Map<String, String> model = getModelRouting("skill/generate");

        if (piAdapter != null) {
            return generateViaPi(intent, existingSummary, model);
        }
        return generateViaLlm(intent, existingSummary);
    }

    public String refine(String skillId, String feedback) {
        if (!enabled()) {
            eventBus.publish(new KernelEvent.PlanStep("error",
                "LLM 未配置。请设置 OPENAI_API_KEY 环境变量或启动 PI 进程"));
            return null;
        }

        Path skillDir = Paths.get(skillsBaseDir, skillId);
        Path genomeDir = skillDir.resolve("genome");
        if (!Files.isDirectory(skillDir)) {
            eventBus.publish(new KernelEvent.PlanStep("error",
                "Skill 目录不存在: " + skillId));
            return null;
        }

        String skillJson, uiJson, handlersJs;
        try {
            skillJson = Files.readString(skillDir.resolve("skill.json"));
            uiJson = Files.readString(genomeDir.resolve("ui.json"));
            handlersJs = Files.readString(genomeDir.resolve("handlers.js"));
        } catch (Exception e) {
            eventBus.publish(new KernelEvent.PlanStep("error",
                "无法读取 Skill 文件: " + e.getMessage()));
            return null;
        }

        if (piAdapter != null) {
            return refineViaPi(skillId, feedback, skillJson, uiJson, handlersJs);
        }
        return refineViaLlm(skillId, feedback, skillJson, uiJson, handlersJs);
    }

    // ── PI path (primary) ──────────────────────────────────────

    private String generateViaPi(String intent, String existingSummary, Map<String, String> model) {
        eventBus.publish(new KernelEvent.PlanStep("generating",
            "正在生成 Skill（PI: " + model.get("provider") + "/" + model.get("model") + "）..."));

        PiMonoAdapter.SkillGenerationResult genResult;
        try {
            genResult = piAdapter.generate(intent,
                List.of(existingSummary.split("\n")),
                model,
                null, // no memory for new skill
                null  // no current files
            );
        } catch (PiMonoException e) {
            log.error("PI generation failed, falling back to LlmClient", e);
            return generateViaLlm(intent, existingSummary);
        }

        SkillFiles raw = genResult.files();
        SkillFiles normalized = normalizer.normalize(raw);

        // Persist memory if this is a first-time generation
        if (genResult.memoryDelta() != null) {
            try {
                String skillId = extractSkillId(normalized.skillJson());
                memoryService.writeIntent(skillId, intent);
                memoryService.appendEvolution(skillId, "genesis", "user-intent",
                    "Initial generation from intent: " + intent);
                if (genResult.memoryDelta().designDecision() != null) {
                    memoryService.appendDesign(skillId, "Initial Design",
                        genResult.memoryDelta().designDecision());
                }
            } catch (Exception e) {
                log.warn("Failed to persist memory: {}", e.getMessage());
            }
        }

        return auditAndInstall(normalized);
    }

    private String refineViaPi(String skillId, String feedback,
                                String skillJson, String uiJson, String handlersJs) {
        Map<String, String> model = getModelRouting("skill/generate");

        eventBus.publish(new KernelEvent.PlanStep("generating",
            "正在优化 Skill " + skillId + "（PI: " + model.get("provider") + "/" + model.get("model") + "）..."));

        // Build memory context from existing skill
        String originalIntent = memoryService.readIntent(skillId);
        List<Map<String, Object>> evolution = memoryService.readEvolution(skillId, 10);
        String design = memoryService.readDesign(skillId);

        Map<String, Object> memory = null;
        if (originalIntent != null || !evolution.isEmpty() || design != null) {
            memory = new java.util.LinkedHashMap<>();
            if (originalIntent != null) memory.put("originalIntent", originalIntent);
            if (!evolution.isEmpty()) memory.put("recentEvolution", evolution);
            if (design != null) memory.put("recentDecisions", design);
        }

        PiMonoAdapter.SkillGenerationResult genResult;
        try {
            genResult = piAdapter.generate(
                "Refine skill based on feedback: " + feedback,
                List.of(),
                model,
                memory,
                Map.of("skillJson", skillJson, "uiJson", uiJson, "handlersJs", handlersJs)
            );
        } catch (PiMonoException e) {
            log.error("PI refine failed, falling back to LlmClient", e);
            return refineViaLlm(skillId, feedback, skillJson, uiJson, handlersJs);
        }

        SkillFiles raw = genResult.files();
        SkillFiles rawFixed = new SkillFiles(
            forceSkillId(raw.skillJson(), skillId),
            raw.uiJson(),
            raw.handlersJs()
        );
        SkillFiles normalized = normalizer.normalize(rawFixed);

        // Append to evolution log
        memoryService.appendEvolution(skillId, "refine", "user-feedback", feedback);
        if (genResult.memoryDelta() != null && genResult.memoryDelta().designDecision() != null) {
            memoryService.appendDesign(skillId, "Refine: " + feedback.substring(0, Math.min(60, feedback.length())),
                genResult.memoryDelta().designDecision());
        }

        return auditAndInstall(normalized);
    }

    private SkillFiles piRepair(SkillFiles current, String skillId, String error, int attempt) throws PiMonoException {
        Map<String, String> model = getModelRouting("skill/repair");
        String originalIntent = memoryService.readIntent(skillId);
        List<Map<String, Object>> evolution = memoryService.readEvolution(skillId, 5);

        Map<String, Object> memory = null;
        if (originalIntent != null) {
            memory = new java.util.LinkedHashMap<>();
            memory.put("originalIntent", originalIntent);
        }

        return piAdapter.repair(current, skillId, error, attempt, model, memory);
    }

    // ── LlmClient path (fallback) ────────────────────────────────

    private String generateViaLlm(String intent, String existingSummary) {
        eventBus.publish(new KernelEvent.PlanStep("generating",
            "正在生成 Skill（调用 " + llmClient.config().model() + "）..."));

        SkillFiles raw;
        try {
            raw = llmClient.generate(generatePrompt, intent, existingSummary);
        } catch (Exception e) {
            log.error("LLM generation failed", e);
            eventBus.publish(new KernelEvent.PlanStep("error",
                "LLM 调用失败: " + e.getMessage()));
            return null;
        }

        SkillFiles normalized = normalizer.normalize(raw);
        return auditAndInstall(normalized);
    }

    private String refineViaLlm(String skillId, String feedback,
                                 String skillJson, String uiJson, String handlersJs) {
        eventBus.publish(new KernelEvent.PlanStep("generating",
            "正在优化 Skill " + skillId + "（调用 " + llmClient.config().model() + "）..."));

        String refinePromptWithId = refinePrompt.replace("{ORIGINAL_SKILL_ID}", skillId);

        SkillFiles raw;
        try {
            raw = llmClient.refine(refinePromptWithId, skillJson, uiJson, handlersJs, feedback);
        } catch (Exception e) {
            log.error("LLM refine failed", e);
            eventBus.publish(new KernelEvent.PlanStep("error",
                "LLM 调用失败: " + e.getMessage()));
            return null;
        }

        SkillFiles rawFixed = new SkillFiles(
            forceSkillId(raw.skillJson(), skillId),
            raw.uiJson(),
            raw.handlersJs()
        );
        SkillFiles normalized = normalizer.normalize(rawFixed);
        return auditAndInstall(normalized);
    }

    private SkillFiles llmRepair(SkillFiles current, String error, int attempt) throws Exception {
        return llmClient.repair(repairPrompt, current, error, attempt);
    }

    // ── Shared: Audit + Repair loop + Install ────────────────────

    private String auditAndInstall(SkillFiles initialFiles) {
        eventBus.publish(new KernelEvent.PlanStep("normalizing", "正在规范化输出..."));
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

                SkillGenome genome = new SkillGenome(finalFiles.uiJson(), finalFiles.handlersJs());

                eventBus.publish(new KernelEvent.PlanStep("auditing",
                    "正在审计 Skill " + manifest.id() + "..."));

                astAuditor.audit(manifest, genome);
                guardian.check(manifest.id(), genome);

                return installSkill(manifest, finalFiles);

            } catch (GateException e) {
                String errorDetail = "[" + e.getCode() + "] " + e.getMessage();
                log.warn("Audit failed (attempt {}): {}", attempt + 1, errorDetail);

                String skillId = extractSkillId(finalFiles.skillJson());
                eventBus.publish(new KernelEvent.RepairAttempt(skillId, attempt + 1, errorDetail));

                if (attempt >= maxRepairAttempts) {
                    eventBus.publish(new KernelEvent.PlanStep("error",
                        "修复已耗尽（" + maxRepairAttempts + " 次），最后的错误: " + errorDetail));
                    return null;
                }

                eventBus.publish(new KernelEvent.PlanStep("repairing",
                    "第 " + (attempt + 1) + "/" + maxRepairAttempts + " 次修复中: " + e.getCode()));

                try {
                    SkillFiles repaired;
                    if (piAdapter != null) {
                        repaired = piRepair(finalFiles, skillId, errorDetail, attempt + 1);
                    } else {
                        repaired = llmRepair(finalFiles, errorDetail, attempt + 1);
                    }
                    SkillFiles repairedFixed = new SkillFiles(
                        forceSkillId(repaired.skillJson(), skillId),
                        repaired.uiJson(),
                        repaired.handlersJs()
                    );
                    finalFiles = normalizer.normalize(repairedFixed);
                } catch (Exception repairEx) {
                    log.error("Repair call failed", repairEx);
                    eventBus.publish(new KernelEvent.PlanStep("error",
                        "修复调用失败: " + repairEx.getMessage()));
                    return null;
                }
            }
        }
        return null;
    }

    // ── Install generated skill ──────────────────────────────────

    private String installSkill(SkillManifest manifest, SkillFiles files) {
        String skillId = manifest.id();

        eventBus.publish(new KernelEvent.PlanStep("installing",
            "正在安装 Skill: " + skillId + "..."));

        try {
            Path skillDir = Paths.get(skillsBaseDir, skillId);
            Path genomeDir = skillDir.resolve("genome");

            if (Files.exists(skillDir)) {
                try (var walk = Files.walk(skillDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
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
                eventBus.publish(new KernelEvent.PlanStep("error",
                    "Skill 加载失败: " + e.getMessage()));
                return null;
            }

            skillManager.activate(skillId);

            eventBus.publish(new KernelEvent.PlanStep("done",
                "Skill " + skillId + " 已生成并激活"));
            return skillId;

        } catch (Exception e) {
            log.error("Failed to install generated skill {}: {}", skillId, e.getMessage());
            eventBus.publish(new KernelEvent.PlanStep("error",
                "安装失败: " + e.getMessage()));
            return null;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String existingSkillsSummary() {
        var skills = skillManager.listSkills();
        if (skills.isEmpty()) return "（暂无已安装的 Skill）";
        return skills.stream()
            .map(s -> "- " + s.id() + " (名称: " + s.name() + ", 版本: " + s.version() + ")")
            .collect(Collectors.joining("\n"));
    }

    private Map<String, String> getModelRouting(String taskType) {
        return storeService.getModelRouting(taskType);
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

    private String loadResource(String name) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to load resource {}: {}", name, e.getMessage());
            return "";
        }
    }
}
