package com.wuwei.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import com.wuwei.llm.AgentFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PageIndex-style skill indexer.
 * On install/update, reads skill genome files and uses LLM to extract
 * structured metadata (capabilities, patterns, function summaries, component summaries).
 * Stores the master index as ~/.wuwei/skill-index.json.
 */
@Component
public class SkillIndexer {

    private static final Logger log = LoggerFactory.getLogger(SkillIndexer.class);
    private static final Path INDEX_PATH = Paths.get(
        System.getProperty("user.home"), ".wuwei", "skill-index.json");

    private final ObjectMapper mapper;
    private final AgentFactory agentFactory;
    private SkillIndex cachedIndex;

    public SkillIndexer(ObjectMapper mapper, AgentFactory agentFactory) {
        this.mapper = mapper;
        this.agentFactory = agentFactory;
    }

    /** Load the current master index from disk. */
    public SkillIndex loadIndex() {
        if (cachedIndex != null) return cachedIndex;
        try {
            if (Files.exists(INDEX_PATH)) {
                cachedIndex = mapper.readValue(INDEX_PATH.toFile(), SkillIndex.class);
                log.info("Loaded skill index: {} skills", cachedIndex.skills().size());
            } else {
                cachedIndex = new SkillIndex(1, Instant.now().toString(), new LinkedHashMap<>());
            }
        } catch (Exception e) {
            log.warn("Failed to load skill index, starting fresh: {}", e.getMessage());
            cachedIndex = new SkillIndex(1, Instant.now().toString(), new LinkedHashMap<>());
        }
        return cachedIndex;
    }

    /** Save the master index to disk. */
    private void saveIndex(SkillIndex idx) {
        try {
            Files.createDirectories(INDEX_PATH.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(INDEX_PATH.toFile(), idx);
            cachedIndex = idx;
            log.info("Saved skill index: {} skills", idx.skills().size());
        } catch (IOException e) {
            log.error("Failed to save skill index: {}", e.getMessage());
        }
    }

    /**
     * Build or rebuild the index entry for a single skill.
     * Reads genome files, sends to LLM for structured extraction.
     */
    public SkillIndex.SkillEntry indexSkill(Path skillDir, String skillId) {
        try {
            Path genomeDir = skillDir.resolve("genome");
            if (!Files.isDirectory(genomeDir)) return null;

            // Collect source files
            Map<String, String> sources = new LinkedHashMap<>();
            Files.walk(genomeDir)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".js") || n.endsWith(".json") || n.endsWith(".md");
                })
                .forEach(p -> {
                    try {
                        String key = genomeDir.relativize(p).toString().replace('\\', '/');
                        sources.put(key, Files.readString(p));
                    } catch (IOException ignored) {}
                });

            if (sources.isEmpty()) return null;

            // Build prompt for LLM
            StringBuilder prompt = new StringBuilder();
            prompt.append("分析以下技能源码，提取结构化信息。输出纯 JSON（不要 markdown 代码块标记）：\n\n");
            for (var entry : sources.entrySet()) {
                String content = entry.getValue();
                // Truncate long files
                if (content.length() > 4000) {
                    content = content.substring(0, 4000) + "\n... (truncated)";
                }
                prompt.append("=== ").append(entry.getKey()).append(" ===\n");
                prompt.append(content).append("\n\n");
            }
            prompt.append("""
                输出格式：
                {
                  "name": "技能名称",
                  "description": "一句话描述",
                  "capabilities": ["database", "crypto", "ui"],
                  "patterns": ["CRUD", "encryption", "pagination"],
                  "functions": [{"name": "onSave", "summary": "保存数据到数据库"}],
                  "components": [{"id": "main-table", "summary": "主数据表格"}],
                  "complexity": "simple|medium|complex"
                }
                注意：
                - capabilities 只能是: database, ui, crypto, websearch, network, file, storage, ai, os, threejs
                - patterns 是代码中使用的设计模式或技术特征（如 CRUD, encryption, pagination, surfaceUpdate, DataModel binding）
                - functions 列出所有 onXxx 处理函数及其功能摘要
                - components 列出 UI 组件 ID 及其用途
                - complexity: simple(1-3个函数) medium(4-8个) complex(9+)
                """);

            String response = agentFactory.chat(prompt.toString());
            if (response == null || response.isBlank()) return null;

            // Parse LLM response — strip any markdown fences
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }

            SkillIndex.SkillEntry entry = mapper.readValue(json, SkillIndex.SkillEntry.class);
            log.info("Indexed skill {}: {} caps, {} patterns, {} functions",
                skillId,
                entry.capabilities() != null ? entry.capabilities().size() : 0,
                entry.patterns() != null ? entry.patterns().size() : 0,
                entry.functions() != null ? entry.functions().size() : 0);
            return entry;

        } catch (Exception e) {
            log.error("Failed to index skill {}: {}", skillId, e.getMessage());
            return null;
        }
    }

    /**
     * Rebuild the entire index by walking all installed skills.
     * Called on startup or when index is stale.
     */
    public void rebuildAll() {
        Path skillsBase = Paths.get(System.getProperty("user.home"), ".wuwei", "skills");
        if (!Files.isDirectory(skillsBase)) return;
        log.info("Rebuilding skill index from {}", skillsBase);
        // Start from existing quick entries, replace with full metadata as LLM completes
        SkillIndex existing = loadIndex();
        Map<String, SkillIndex.SkillEntry> skills = new LinkedHashMap<>(existing.skills());
        try (var dirs = Files.list(skillsBase)) {
            dirs.filter(Files::isDirectory).forEach(skillDir -> {
                String skillId = skillDir.getFileName().toString();
                SkillIndex.SkillEntry entry = indexSkill(skillDir, skillId);
                if (entry != null) {
                    skills.put(skillId, entry); // replace quick entry with full metadata
                } else if (!skills.containsKey(skillId)) {
                    // Couldn't index, keep whatever was there (or quick entry from startup)
                }
            });
        } catch (IOException e) {
            log.error("Failed to walk skills dir: {}", e.getMessage());
        }
        saveIndex(new SkillIndex(1, Instant.now().toString(), skills));
        log.info("Index rebuild complete: {} skills indexed", skills.size());
    }

    /**
     * Quick add: registers skill with name + capabilities only, no LLM call.
     * Used during startupLoad for fast indexing. Full metadata filled by rebuildAll.
     */
    public void addQuick(String skillId, String name, String description, List<String> capabilities) {
        SkillIndex idx = loadIndex();
        Map<String, SkillIndex.SkillEntry> skills = new LinkedHashMap<>(idx.skills());
        var entry = new SkillIndex.SkillEntry(name, description, capabilities,
            List.of(), List.of(), List.of(), "unknown");
        skills.put(skillId, entry);
        saveIndex(new SkillIndex(idx.version(), Instant.now().toString(), skills));
    }

    /** Add or update a skill in the index. Called on install/update. */
    public void addOrUpdate(String skillId, Path skillDir) {
        SkillIndex idx = loadIndex();
        Map<String, SkillIndex.SkillEntry> skills = new LinkedHashMap<>(idx.skills());
        SkillIndex.SkillEntry entry = indexSkill(skillDir, skillId);
        if (entry != null) {
            skills.put(skillId, entry);
            saveIndex(new SkillIndex(idx.version(), Instant.now().toString(), skills));
        }
    }

    /** Remove a skill from the index. Called on uninstall. */
    public void remove(String skillId) {
        SkillIndex idx = loadIndex();
        Map<String, SkillIndex.SkillEntry> skills = new LinkedHashMap<>(idx.skills());
        if (skills.remove(skillId) != null) {
            saveIndex(new SkillIndex(idx.version(), Instant.now().toString(), skills));
            log.info("Removed skill {} from index", skillId);
        }
    }

    /**
     * Retrieve top-K relevant skills for a user query.
     * Uses LLM to traverse the index tree and find matches.
     */
    public List<Map<String, Object>> search(String query, int topK) {
        SkillIndex idx = loadIndex();
        if (idx.skills().isEmpty()) return List.of();

        // Build a compact index summary for the LLM
        StringBuilder tree = new StringBuilder();
        tree.append("已安装技能索引：\n\n");
        for (var entry : idx.skills().entrySet()) {
            var s = entry.getValue();
            tree.append("[").append(entry.getKey()).append("] ");
            tree.append(s.name()).append(" — ").append(s.description()).append("\n");
            tree.append("  能力: ").append(String.join(", ", s.capabilities())).append("\n");
            if (s.patterns() != null && !s.patterns().isEmpty()) {
                tree.append("  模式: ").append(String.join(", ", s.patterns())).append("\n");
            }
            if (s.functions() != null && !s.functions().isEmpty()) {
                tree.append("  函数: ");
                tree.append(s.functions().stream()
                    .map(f -> f.name() + "(" + f.summary() + ")")
                    .collect(Collectors.joining("; ")));
                tree.append("\n");
            }
            tree.append("  复杂度: ").append(s.complexity()).append("\n\n");
        }

        String prompt = tree.toString() + "\n" +
            "用户需求: " + query + "\n\n" +
            "从以上技能索引中找出最相关的 " + topK + " 个技能。\n" +
            "输出纯 JSON 数组（不要 markdown 标记），每个元素包含 skillId, relevance(0-1), reason:\n" +
            "[{\"skillId\": \"jiduobao\", \"relevance\": 0.95, \"reason\": \"支持加密增删改查\"}]";

        try {
            String response = agentFactory.chat(prompt);
            if (response == null || response.isBlank()) return List.of();
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = mapper.readValue(json, List.class);
            return results;
        } catch (Exception e) {
            log.warn("Skill search failed: {}", e.getMessage());
            return List.of();
        }
    }
}
