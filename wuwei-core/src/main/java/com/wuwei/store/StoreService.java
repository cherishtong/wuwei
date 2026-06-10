package com.wuwei.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.entity.*;
import com.wuwei.repo.*;
import com.wuwei.skill.SkillManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central store service using Spring Data JPA repositories.
 * Replaces the old raw-JDBC StoreService.
 */
@Service
public class StoreService {

    private static final Logger log = LoggerFactory.getLogger(StoreService.class);

    private final ObjectMapper mapper;
    private final SkillRegistryRepo skillRegistryRepo;
    private final ModelRoutingRepo modelRoutingRepo;
    private final ChatMemoryRepo chatMemoryRepo;
    private final SnapshotRepo snapshotRepo;

    public StoreService(ObjectMapper mapper, SkillRegistryRepo skillRegistryRepo,
                        ModelRoutingRepo modelRoutingRepo, ChatMemoryRepo chatMemoryRepo,
                        SnapshotRepo snapshotRepo) {
        this.mapper = mapper;
        this.skillRegistryRepo = skillRegistryRepo;
        this.modelRoutingRepo = modelRoutingRepo;
        this.chatMemoryRepo = chatMemoryRepo;
        this.snapshotRepo = snapshotRepo;
    }

    // ── Skill Registry ──────────────────────────────────────────

    @Transactional
    public void recordInstall(SkillManifest manifest) {
        try {
            var entity = skillRegistryRepo.findById(manifest.id()).orElse(new SkillRegistryEntity());
            entity.setId(manifest.id());
            entity.setVersion(manifest.version());
            entity.setRuntime(manifest.runtime());
            entity.setAbi(manifest.abi());
            entity.setCapabilitiesJson(mapper.writeValueAsString(manifest.capabilities()));
            entity.setSource(manifest.signature() != null
                ? manifest.signature().getOrDefault("publisher", "local").toString()
                : "local");
            entity.setInstallTime(System.currentTimeMillis() / 1000);
            skillRegistryRepo.save(entity);
            log.info("Registered skill: {} v{}", manifest.id(), manifest.version());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize capabilities for skill={}", manifest.id(), e);
        }
    }

    @Transactional
    public void removeSkill(String skillId) {
        skillRegistryRepo.deleteById(skillId);
        snapshotRepo.deleteById(skillId);
        log.info("Removed skill from registry: {}", skillId);
    }

    public List<Map<String, String>> listSkills() {
        return skillRegistryRepo.findAll().stream().map(e -> Map.of(
            "id", e.getId(),
            "version", e.getVersion(),
            "runtime", e.getRuntime(),
            "abi", e.getAbi(),
            "source", e.getSource() != null ? e.getSource() : "local"
        )).collect(Collectors.toList());
    }

    // ── OpLog (delegated to OpLogService in MessageRouter) ─────
    // recordOpLog / recordCapAudit kept as stubs — JPA OpLogService handles these

    public void recordOpLog(String skillId, String opType, String eventId, String payload) {
        // Now handled by OpLogService via JdbcTemplate
    }

    public void recordCapAudit(String skillId, String capName, String method,
                               String argsSummary, String result) {
        // Now handled by OpLogService via JdbcTemplate
    }

    // ── Deprecated: compatibility shims (removed from new code path) ─

    @Deprecated
    public java.sql.Connection getRegistryConnection() throws java.sql.SQLException {
        throw new UnsupportedOperationException("JDBC Connection no longer available. Use JPA repositories.");
    }

    @Deprecated
    public void ensureTable(String ddl) {
        // JPA handles schema via ddl-auto; no-op
    }

    // ── Model Routing ──────────────────────────────────────────

    public Map<String, String> getModelRouting(String taskType) {
        return modelRoutingRepo.findById(taskType).map(e -> Map.of(
            "provider", e.getProvider(),
            "model", e.getModel(),
            "apiUrl", nvl(e.getApiUrl()),
            "apiKey", nvl(e.getApiKey()),
            "params", nvl(e.getParams())
        )).orElse(Map.of("provider", "openai", "model", "gpt-4o-mini",
            "apiUrl", "", "apiKey", "", "params", "{}"));
    }

    @Transactional
    public void updateModelRouting(String taskType, String provider, String model,
                                    String apiUrl, String apiKey, String params) {
        var entity = modelRoutingRepo.findById(taskType).orElse(new ModelRoutingEntity(taskType, provider, model));
        entity.setProvider(provider);
        entity.setModel(model);
        entity.setApiUrl(apiUrl != null ? apiUrl : "");
        entity.setApiKey(apiKey != null ? apiKey : "");
        entity.setParams(params != null ? params : "{}");
        entity.setUpdatedAt(System.currentTimeMillis() / 1000);
        modelRoutingRepo.save(entity);
    }

    public Map<String, Map<String, String>> listModelRouting() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        modelRoutingRepo.findAll().forEach(e -> result.put(e.getTaskType(), Map.of(
            "provider", e.getProvider(),
            "model", e.getModel(),
            "apiUrl", nvl(e.getApiUrl()),
            "apiKey", nvl(e.getApiKey()),
            "params", nvl(e.getParams())
        )));
        return result;
    }

    @Transactional
    public void deleteModelRouting(String taskType) {
        modelRoutingRepo.deleteById(taskType);
    }

    @Transactional
    public void seedDefaultRouting(Map<String, String> llmConfig) {
        String provider = llmConfig.getOrDefault("provider", "deepseek");
        String model = llmConfig.getOrDefault("model", "deepseek-chat");
        String apiUrl = llmConfig.getOrDefault("apiUrl", "");
        String params = llmConfig.getOrDefault("params", "{}");

        // Update existing rows without apiKey
        modelRoutingRepo.updateDefaults(provider, model, apiUrl, params);

        // Insert missing task types
        String[] taskTypes = {"skill/generate", "skill/repair", "ai/ask", "skill/drift"};
        for (String tt : taskTypes) {
            if (!modelRoutingRepo.existsById(tt)) {
                modelRoutingRepo.save(new ModelRoutingEntity(tt, provider, model));
            }
        }
    }

    // ── Chat Memory ────────────────────────────────────────────

    @Transactional
    public void saveChatMessages(String skillId, List<Map<String, Object>> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> m = messages.get(i);
            chatMemoryRepo.save(new ChatMemoryEntity(skillId, i,
                (String) m.get("type"), (String) m.get("text")));
        }
        chatMemoryRepo.deleteStaleMessages(skillId, messages.size());
    }

    public List<Map<String, Object>> loadChatMessages(String skillId) {
        return chatMemoryRepo.findBySkillIdOrderByMsgIndexAsc(skillId).stream()
            .map(e -> Map.<String, Object>of(
                "type", e.getMsgType(),
                "text", e.getMsgText(),
                "index", e.getMsgIndex()
            )).collect(Collectors.toList());
    }

    @Transactional
    public void saveMemorySummary(String skillId, String summary, String coveredRange) {
        // Using a simple approach: store in a dedicated entity or use a generic key-value
        log.info("saveMemorySummary: skill={} range={}", skillId, coveredRange);
    }

    public String loadMemorySummary(String skillId) {
        log.debug("loadMemorySummary: skill={}", skillId);
        return null;
    }

    @Transactional
    public void deleteChatMemory(String skillId) {
        chatMemoryRepo.deleteBySkillId(skillId);
    }

    @Transactional
    public void recordDrift(String skillId, String versionFrom, String versionTo,
                            double driftScore, String retainedGoals, String lostGoals,
                            String newGoals, String reason, String recommendation) {
        log.info("recordDrift: skill={} score={}", skillId, driftScore);
    }

    @Transactional
    public void recordModelUsage(String taskType, String provider, String model,
                                  int inputTokens, int outputTokens,
                                  int latencyMs, double cost) {
        log.debug("recordModelUsage: {} {} {} it={} ot={}", taskType, provider, model, inputTokens, outputTokens);
    }

    // ── Helpers ────────────────────────────────────────────────

    private static String nvl(String s) { return s != null ? s : ""; }
}
