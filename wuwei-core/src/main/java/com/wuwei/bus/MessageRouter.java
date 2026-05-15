package com.wuwei.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.bus.event.KernelEvent;
import com.wuwei.capability.CapabilityManager;
import com.wuwei.llm.SkillGenerator;
import com.wuwei.skill.SkillManager;
import io.helidon.websocket.WsSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final ObjectMapper mapper;
    private final EventBus eventBus;
    private final SkillManager skillManager;
    private final CapabilityManager capManager;
    private final SkillGenerator skillGenerator;

    public MessageRouter(ObjectMapper mapper, EventBus eventBus,
                         SkillManager skillManager, CapabilityManager capManager,
                         SkillGenerator skillGenerator) {
        this.mapper = mapper;
        this.eventBus = eventBus;
        this.skillManager = skillManager;
        this.capManager = capManager;
        this.skillGenerator = skillGenerator;
    }

    public EventBus eventBus() {
        return eventBus;
    }

    public void route(WsSession session, String payload) {
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
                case "revoke-cap"       -> handleRevokeCap(session, msg);
                default -> sendError(session, "system", "UNKNOWN_TYPE", "Unknown message type: " + type);
            }
        } catch (Exception e) {
            log.error("Message routing error", e);
            sendError(session, "system", "ROUTER_ERROR", e.getMessage());
        }
    }

    private void handleUserIntent(WsSession session, JsonNode msg) {
        String text = msg.has("payload") ? msg.get("payload").get("text").asText() : "";
        if (text.isBlank()) {
            eventBus.publish(new KernelEvent.PlanStep("error", "意图描述不能为空"));
            return;
        }
        log.info("user-intent: {}", text);

        if (!skillGenerator.enabled()) {
            eventBus.publish(new KernelEvent.PlanStep("error",
                "LLM 未配置。请设置 OPENAI_API_KEY 环境变量"));
            return;
        }

        // Run generation in background thread (may take 30-60s)
        new Thread(() -> {
            String resultId = skillGenerator.generate(text);
            log.info("Generation result: {}", resultId != null ? resultId : "FAILED");
        }, "llm-generate").start();
    }

    private void handleRefineSkill(WsSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        String feedback = msg.has("payload") ? msg.get("payload").get("feedback").asText() : "";
        if (skillId.equals("unknown") || feedback.isBlank()) {
            eventBus.publish(new KernelEvent.PlanStep("error", "优化请求缺少 skillId 或 feedback"));
            return;
        }
        log.info("refine-skill: skill={} feedback={}", skillId, feedback);

        if (!skillGenerator.enabled()) {
            eventBus.publish(new KernelEvent.PlanStep("error",
                "LLM 未配置。请设置 OPENAI_API_KEY 环境变量"));
            return;
        }

        // Run refine in background thread (may take 30-60s)
        new Thread(() -> {
            String resultId = skillGenerator.refine(skillId, feedback);
            log.info("Refine result: {}", resultId != null ? resultId : "FAILED");
        }, "llm-refine").start();
    }

    private void handleEvent(WsSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        String eventId = extractText(msg, "eventId");
        Map<String, Object> inputs = extractMap(msg, "inputs");
        System.out.println("[Router] handle-event: skill=" + skillId + " event=" + eventId + " inputs=" + inputs);
        skillManager.handleEvent(skillId, eventId, inputs);
    }

    private void handleInstallSkill(WsSession session, JsonNode msg) {
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

    private void handleUninstallSkill(WsSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        log.info("uninstall-skill: {}", skillId);
        capManager.cancelPendingGates(skillId);
        skillManager.uninstall(skillId);
    }

    private void handleActivateSkill(WsSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        log.info("activate-skill: {}", skillId);
        skillManager.activate(skillId);
    }

    private void handleDeactivateSkill(WsSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        log.info("deactivate-skill: {}", skillId);
        skillManager.deactivate(skillId);
    }

    private void handleListSkills(WsSession session) {
        List<KernelEvent.SkillMeta> skills = skillManager.listSkills();
        eventBus.publish(new KernelEvent.SkillList(skills));
    }

    private void handleConfirmGate(WsSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        String capName = extractText(msg, "capName");
        boolean approved = msg.has("approved") && msg.get("approved").asBoolean();
        log.info("confirm-gate: skill={} cap={} approved={}", skillId, capName, approved);
        capManager.resolveGate(skillId, capName, approved);
    }

    private void handleSetRateLimit(WsSession session, JsonNode msg) {
        boolean enabled = msg.has("enabled") && msg.get("enabled").asBoolean();
        log.info("set-rate-limit: {}", enabled);
        eventBus.setRateLimitEnabled(enabled);
        eventBus.publishTo(session, new KernelEvent.SystemNotify(
            "事件限流", enabled ? "事件限流已开启" : "事件限流已关闭"));
    }

    private void handleGetRateLimit(WsSession session) {
        boolean enabled = eventBus.isRateLimitEnabled();
        eventBus.publishTo(session, new KernelEvent.SystemNotify(
            "事件限流状态", enabled ? "当前限流状态：开启" : "当前限流状态：关闭"));
    }

    private void handleRevokeCap(WsSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        String capName = extractText(msg, "capName");
        log.info("revoke-cap: skill={} cap={}", skillId, capName);
        capManager.revoke(skillId, capName);
    }

    // ── Workbench: view skill source ────────────────────────────

    private void handleGetSkillSource(WsSession session, JsonNode msg) {
        String skillId = extractText(msg, "skillId");
        String home = System.getProperty("user.home");
        Path skillDir = Paths.get(home, ".wuwei", "skills", skillId);

        if (!Files.isDirectory(skillDir)) {
            eventBus.publishTo(session, new KernelEvent.KernelError(skillId, "SKILL_NOT_FOUND",
                "Skill directory not found"));
            return;
        }

        try {
            Map<String, String> sources = new LinkedHashMap<>();
            sources.put("skillId", skillId);

            Path manifestPath = skillDir.resolve("skill.json");
            if (Files.exists(manifestPath)) {
                sources.put("skillJson", Files.readString(manifestPath));
            }
            Path uiPath = skillDir.resolve("genome").resolve("ui.json");
            if (Files.exists(uiPath)) {
                sources.put("uiJson", Files.readString(uiPath));
            }
            Path handlersPath = skillDir.resolve("genome").resolve("handlers.js");
            if (Files.exists(handlersPath)) {
                sources.put("handlersJs", Files.readString(handlersPath));
            }

            // Send as a custom event directly to the requesting session
            String json = mapper.writeValueAsString(Map.of(
                "type", "skill-source",
                "skillId", skillId,
                "skillJson", sources.getOrDefault("skillJson", ""),
                "uiJson", sources.getOrDefault("uiJson", ""),
                "handlersJs", sources.getOrDefault("handlersJs", "")
            ));
            eventBus.publishTo(session, new KernelEvent.SystemNotify(
                "源代码: " + skillId,
                "已加载 " + sources.size() + " 个文件"));
            session.send(json, true);
        } catch (Exception e) {
            log.error("Failed to read skill source {}: {}", skillId, e.getMessage());
            eventBus.publishTo(session, new KernelEvent.KernelError(skillId, "SOURCE_READ_ERROR",
                "无法读取源代码: " + e.getMessage()));
        }
    }

    // ── helpers ──────────────────────────────────────────────────

    private String extractText(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : "unknown";
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

    private void sendError(WsSession session, String skillId, String code, String message) {
        eventBus.publish(new KernelEvent.KernelError(skillId, code, message));
    }
}
