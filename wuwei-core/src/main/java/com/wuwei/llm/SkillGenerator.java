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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/**
 * Full Skill generation pipeline:
 * intent → LLM → parse → normalize → audit → repair loop (max 2) → save → load → activate.
 *
 * Publishes PlanStep and RepairAttempt events at each stage so the frontend
 * can show progress.
 */
public class SkillGenerator {

    private static final Logger log = LoggerFactory.getLogger(SkillGenerator.class);

    private final LlmClient llmClient;
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

    public SkillGenerator(LlmClient llmClient, Normalizer normalizer,
                          AstAuditor astAuditor, EcosystemGuardian guardian,
                          SkillManager skillManager, SnapshotService snapshotService,
                          EventBus eventBus, ObjectMapper mapper,
                          int maxRepairAttempts) {
        this.llmClient = llmClient;
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
        return llmClient.config().enabled();
    }

    /**
     * Refine an existing Skill based on user feedback.
     * Reads current files, sends to LLM with refine prompt, re-installs the skill.
     */
    public String refine(String skillId, String feedback) {
        if (!enabled()) {
            eventBus.publish(new KernelEvent.PlanStep("error",
                "LLM 未配置。请设置 " + llmClient.config().apiKeyEnv() + " 环境变量"));
            return null;
        }

        // Read current skill files from disk
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

        // ── Stage 1: LLM refine ────────────────────────────────
        eventBus.publish(new KernelEvent.PlanStep("generating",
            "正在优化 Skill " + skillId + "（调用 " + llmClient.config().model() + "）..."));

        // Inject skillId into refine prompt so LLM knows not to change it
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

        // ── Stage 2: Force-preserve original skill id ───────────
        SkillFiles rawFixed = new SkillFiles(
            forceSkillId(raw.skillJson(), skillId),
            raw.uiJson(),
            raw.handlersJs()
        );

        // ── Stage 3: Normalize ─────────────────────────────────
        eventBus.publish(new KernelEvent.PlanStep("normalizing",
            "正在规范化输出..."));
        SkillFiles normalized = normalizer.normalize(rawFixed);

        // ── Stage 4: Audit + Repair loop ───────────────────────
        SkillFiles finalFiles = normalized;
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

                // Install new version (installSkill handles file rewrite + reload + activate)
                return installSkill(manifest, finalFiles);

            } catch (GateException e) {
                String errorDetail = "[" + e.getCode() + "] " + e.getMessage();
                log.warn("Refine audit failed (attempt {}): {}", attempt + 1, errorDetail);

                eventBus.publish(new KernelEvent.RepairAttempt(skillId, attempt + 1, errorDetail));

                if (attempt >= maxRepairAttempts) {
                    eventBus.publish(new KernelEvent.PlanStep("error",
                        "优化修复已耗尽（" + maxRepairAttempts + " 次），最后的错误: " + errorDetail));
                    return null;
                }

                eventBus.publish(new KernelEvent.PlanStep("repairing",
                    "第 " + (attempt + 1) + "/" + maxRepairAttempts + " 次修复中: " + e.getCode()));

                try {
                    SkillFiles repaired = llmClient.repair(repairPrompt, finalFiles, errorDetail, attempt + 1);
                    // Force-preserve original skill id after repair too
                    SkillFiles repairedFixed = new SkillFiles(
                        forceSkillId(repaired.skillJson(), skillId),
                        repaired.uiJson(),
                        repaired.handlersJs()
                    );
                    finalFiles = normalizer.normalize(repairedFixed);
                } catch (Exception repairEx) {
                    log.error("Repair LLM call failed", repairEx);
                    eventBus.publish(new KernelEvent.PlanStep("error",
                        "修复调用失败: " + repairEx.getMessage()));
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Full generation pipeline. Returns the skill ID on success, null on failure.
     * Publishes PlanStep at each stage.
     */
    public String generate(String intent) {
        if (!enabled()) {
            eventBus.publish(new KernelEvent.PlanStep("error",
                "LLM 未配置。请设置 " + llmClient.config().apiKeyEnv() + " 环境变量并配置 wuwei.json"));
            return null;
        }

        String existingSummary = existingSkillsSummary();

        // ── Stage 1: LLM generation ───────────────────────────
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

        // ── Stage 2: Normalize ───────────────────────────────
        eventBus.publish(new KernelEvent.PlanStep("normalizing",
            "正在规范化输出..."));
        SkillFiles normalized = normalizer.normalize(raw);

        // ── Stage 3: Audit + Repair loop ─────────────────────
        SkillFiles finalFiles = normalized;
        for (int attempt = 0; attempt <= maxRepairAttempts; attempt++) {
            try {
                // Validate JSON parseable
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

                // Audit passed — install the skill
                return installSkill(manifest, finalFiles);

            } catch (GateException e) {
                String errorDetail = "[" + e.getCode() + "] " + e.getMessage();
                log.warn("Audit failed (attempt {}): {}", attempt + 1, errorDetail);

                eventBus.publish(new KernelEvent.RepairAttempt(
                    extractSkillId(finalFiles.skillJson()), attempt + 1, errorDetail));

                if (attempt >= maxRepairAttempts) {
                    eventBus.publish(new KernelEvent.PlanStep("error",
                        "修复已耗尽（" + maxRepairAttempts + " 次），最后的错误: " + errorDetail));
                    return null;
                }

                // ── Repair ────────────────────────────────────
                eventBus.publish(new KernelEvent.PlanStep("repairing",
                    "第 " + (attempt + 1) + "/" + maxRepairAttempts + " 次修复中: " + e.getCode()));

                try {
                    finalFiles = normalizer.normalize(
                        llmClient.repair(repairPrompt, finalFiles, errorDetail, attempt + 1));
                } catch (Exception repairEx) {
                    log.error("Repair LLM call failed", repairEx);
                    eventBus.publish(new KernelEvent.PlanStep("error",
                        "修复调用失败: " + repairEx.getMessage()));
                    return null;
                }
            }
        }
        return null; // unreachable
    }

    // ── Install generated skill ──────────────────────────────

    private String installSkill(SkillManifest manifest, SkillFiles files) {
        String skillId = manifest.id();

        eventBus.publish(new KernelEvent.PlanStep("installing",
            "正在安装 Skill: " + skillId + "..."));

        try {
            Path skillDir = Paths.get(skillsBaseDir, skillId);
            Path genomeDir = skillDir.resolve("genome");

            if (Files.exists(skillDir)) {
                // Remove old version
                try (var walk = Files.walk(skillDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                }
            }
            Files.createDirectories(genomeDir);

            // Write skill.json
            String prettySkillJson = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(manifest);
            Files.writeString(skillDir.resolve("skill.json"), prettySkillJson);

            // Write genome/ui.json
            String prettyUiJson = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(mapper.readTree(files.uiJson()));
            Files.writeString(genomeDir.resolve("ui.json"), prettyUiJson);

            // Write genome/handlers.js
            Files.writeString(genomeDir.resolve("handlers.js"), files.handlersJs());

            log.info("Generated skill files written to {}", skillDir);

            // Clear stale snapshot so the new files are used (not overwritten by restore)
            snapshotService.delete(skillId);

            // Load and activate through SkillManager (reuses audit + activate pipeline)
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

    // ── Helpers ──────────────────────────────────────────────

    private String existingSkillsSummary() {
        var skills = skillManager.listSkills();
        if (skills.isEmpty()) return "（暂无已安装的 Skill）";
        return skills.stream()
            .map(s -> "- " + s.id() + " (名称: " + s.name() + ", 版本: " + s.version() + ")")
            .collect(Collectors.joining("\n"));
    }

    /**
     * Force-set the id field in skillJson to the given skillId.
     * Prevents LLM from changing the skill id during refine/repair.
     */
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
