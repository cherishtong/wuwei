package com.wuwei.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.bus.EventBus;
import com.wuwei.bus.event.KernelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Business adapter — the only class that knows the Pi JSON-RPC API.
 * Translates Java calls into Pi method invocations and parses results.
 */
public class PiMonoAdapter {

    private static final Logger log = LoggerFactory.getLogger(PiMonoAdapter.class);

    private final PiMonoClient client;
    private final EventBus eventBus;
    private final ObjectMapper mapper;

    public PiMonoAdapter(PiMonoClient client, EventBus eventBus, ObjectMapper mapper) {
        this.client = client;
        this.eventBus = eventBus;
        this.mapper = mapper;
    }

    // ── Skill generation ──────────────────────────────────────────

    public SkillGenerationResult generate(
            String intent,
            List<String> existingSkills,
            Map<String, String> model,
            Map<String, Object> memory,
            Map<String, String> currentFiles) throws PiMonoException {

        log.info("Requesting Pi to generate Skill: {}", intent);

        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("intent", intent);
        params.put("existingSkills", existingSkills);
        params.put("model", model);
        params.put("memory", memory);
        params.put("currentFiles", currentFiles);

        try {
            JsonNode result = client.call("skill/generate", params,
                progress -> log.debug("Generate progress: {} ({}%)", progress.message(), progress.percent()),
                null
            ).get(60, TimeUnit.SECONDS);

            if (result == null) throw new PiMonoException(-32002, "Pi request timed out");

            SkillFiles files = new SkillFiles(
                result.get("skillJson").asText(),
                result.get("uiJson").asText(),
                result.get("handlersJs").asText()
            );

            MemoryDelta memoryDelta = null;
            if (result.has("memoryDelta") && !result.get("memoryDelta").isNull()) {
                JsonNode delta = result.get("memoryDelta");
                List<String> goals = mapper.convertValue(
                    delta.get("newCoreGoals"), List.class);
                String design = delta.get("designDecision").asText();
                memoryDelta = new MemoryDelta(goals, design);
            }

            return new SkillGenerationResult(files, memoryDelta);

        } catch (PiMonoException e) {
            throw e;
        } catch (Exception e) {
            log.error("Pi generation failed", e);
            throw new PiMonoException(-32001, "Pi call failed: " + e.getMessage());
        }
    }

    // ── Skill repair ──────────────────────────────────────────────

    public SkillFiles repair(
            SkillFiles files,
            String skillId,
            String error,
            int attempt,
            Map<String, String> model,
            Map<String, Object> memory) throws PiMonoException {

        log.info("Requesting Pi to repair Skill: {} (attempt {})", skillId, attempt);

        Map<String, Object> params = Map.of(
            "skillId", skillId,
            "error", error,
            "files", Map.of(
                "skillJson", files.skillJson(),
                "uiJson", files.uiJson(),
                "handlersJs", files.handlersJs()
            ),
            "model", model,
            "memory", memory != null ? memory : Map.of(),
            "attempt", attempt
        );

        try {
            JsonNode result = client.call("skill/repair", params, null, null)
                .get(60, TimeUnit.SECONDS);

            if (result == null) throw new PiMonoException(-32002, "Pi repair timed out");

            return new SkillFiles(
                result.get("skillJson").asText(),
                result.get("uiJson").asText(),
                result.get("handlersJs").asText()
            );
        } catch (PiMonoException e) {
            throw e;
        } catch (Exception e) {
            log.error("Pi repair failed", e);
            throw new PiMonoException(-32001, "Pi repair call failed: " + e.getMessage());
        }
    }

    // ── Drift analysis ────────────────────────────────────────────

    public DriftResult analyzeDrift(
            String skillId,
            String originalIntent,
            List<String> coreGoals,
            String currentHandlersJs,
            String proposedChange,
            Map<String, String> model) throws PiMonoException {

        log.info("Requesting Pi to analyze drift for: {}", skillId);

        Map<String, Object> params = Map.of(
            "skillId", skillId,
            "originalIntent", originalIntent,
            "coreGoals", coreGoals,
            "currentHandlersJs", currentHandlersJs,
            "proposedChange", proposedChange,
            "model", model
        );

        try {
            JsonNode result = client.call("skill/analyzeDrift", params, null, null)
                .get(30, TimeUnit.SECONDS);

            if (result == null) throw new PiMonoException(-32002, "Pi drift analysis timed out");

            return new DriftResult(
                result.get("driftScore").asDouble(),
                mapper.convertValue(result.get("retainedGoals"), List.class),
                mapper.convertValue(result.get("lostGoals"), List.class),
                mapper.convertValue(result.get("newGoals"), List.class),
                result.get("reason").asText(),
                result.get("recommendation").asText()
            );
        } catch (PiMonoException e) {
            throw e;
        } catch (Exception e) {
            log.error("Pi drift analysis failed", e);
            throw new PiMonoException(-32001, "Pi drift call failed: " + e.getMessage());
        }
    }

    // ── Runtime AI calls ──────────────────────────────────────────

    public AiResult aiAsk(String skillId, String prompt,
                          Map<String, String> model) throws PiMonoException {
        Map<String, Object> params = Map.of(
            "skillId", skillId,
            "prompt", prompt,
            "model", model
        );

        try {
            JsonNode result = client.call("ai/ask", params, null, null)
                .get(30, TimeUnit.SECONDS);

            if (result == null) throw new PiMonoException(-32002, "Pi ai/ask timed out");

            return new AiResult(
                result.get("status").asInt(),
                result.get("body").asText()
            );
        } catch (PiMonoException e) {
            throw e;
        } catch (Exception e) {
            log.error("Pi ai/ask failed", e);
            throw new PiMonoException(-32001, "Pi ai/ask failed: " + e.getMessage());
        }
    }

    public void aiAskStream(String skillId, String prompt,
                            Map<String, String> model,
                            Consumer<String> onToken,
                            Runnable onDone) {
        Map<String, Object> params = Map.of(
            "skillId", skillId,
            "prompt", prompt,
            "model", model
        );

        client.call("ai/askStream", params, null, onToken)
            .thenRun(onDone)
            .exceptionally(ex -> {
                log.error("Pi ai/askStream failed", ex);
                onDone.run();
                return null;
            });
    }

    // ── Result types ──────────────────────────────────────────────

    public record SkillGenerationResult(SkillFiles files, MemoryDelta memoryDelta) {}

    public record MemoryDelta(List<String> newCoreGoals, String designDecision) {}

    public record DriftResult(
        double driftScore,
        List<String> retainedGoals,
        List<String> lostGoals,
        List<String> newGoals,
        String reason,
        String recommendation
    ) {}

    public record AiResult(int status, String body) {}
}
