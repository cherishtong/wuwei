package com.wuwei.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.skill.SkillManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Central store service using direct JDBC (no connection pool).
 * Single-threaded SQLite access — pool overhead is unnecessary here.
 */
public class StoreService {

    private static final Logger log = LoggerFactory.getLogger(StoreService.class);

    private final ObjectMapper mapper;
    private final String dbPath;
    private Connection conn;

    public StoreService(ObjectMapper mapper) {
        this.mapper = mapper;
        String home = System.getProperty("user.home");
        this.dbPath = Paths.get(home, ".wuwei", "data", "registry.db").toString();
        try {
            Files.createDirectories(Paths.get(dbPath).getParent());
        } catch (Exception e) {
            throw new RuntimeException("Cannot create data directory", e);
        }
    }

    private Connection getConn() throws SQLException {
        if (conn == null || conn.isClosed()) {
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA foreign_keys=ON");
            }
        }
        return conn;
    }

    public void initSchema() {
        try {
            Connection c = getConn();
            InputStream is = getClass().getClassLoader().getResourceAsStream("schema.sql");
            if (is == null) {
                log.warn("schema.sql not found, skipping schema init");
                return;
            }
            String sql = new BufferedReader(new InputStreamReader(is))
                .lines()
                .reduce("", (a, b) -> a + "\n" + b);

            try (Statement stmt = c.createStatement()) {
                for (String part : sql.split(";")) {
                    String stripped = part.replaceAll("(?m)^\\s*--.*$", "").trim();
                    if (!stripped.isEmpty()) {
                        try { stmt.execute(stripped); } catch (SQLException e) {
                            log.warn("Schema statement failed: {} — {}",
                                e.getMessage(), stripped.substring(0, Math.min(80, stripped.length())));
                        }
                    }
                }
            }

            // Migrations: add missing columns to model_routing (ignore if already present)
            try (Statement stmt = c.createStatement()) {
                stmt.execute("ALTER TABLE model_routing ADD COLUMN api_key TEXT DEFAULT ''");
            } catch (SQLException e) { /* already exists */ }
            try (Statement stmt = c.createStatement()) {
                stmt.execute("ALTER TABLE model_routing ADD COLUMN api_url TEXT DEFAULT ''");
            } catch (SQLException e) { /* already exists */ }
            try (Statement stmt = c.createStatement()) {
                stmt.execute("ALTER TABLE model_routing ADD COLUMN params TEXT DEFAULT '{}'");
            } catch (SQLException e) { /* already exists */ }

            // Data migration: migrate legacy openai defaults to deepseek
            try (Statement stmt = c.createStatement()) {
                stmt.execute("UPDATE model_routing SET provider='deepseek', model='deepseek-v4-pro' WHERE provider='openai' AND task_type IN ('skill/generate','skill/repair','ai/ask')");
                stmt.execute("UPDATE model_routing SET provider='deepseek', model='deepseek-v4-flash' WHERE provider='openai' AND task_type='skill/drift'");
            } catch (SQLException e) { /* ignore */ }

            log.info("Schema initialized");
        } catch (Exception e) {
            log.error("Schema init failed", e);
        }
    }

    // ── Skill Registry ──────────────────────────────────────────

    public void recordInstall(SkillManifest manifest) {
        try (PreparedStatement ps = getConn().prepareStatement(
                "INSERT OR REPLACE INTO skill_registry(id, version, runtime, abi, capabilities_json, source) " +
                "VALUES(?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, manifest.id());
            ps.setString(2, manifest.version());
            ps.setString(3, manifest.runtime());
            ps.setString(4, manifest.abi());
            ps.setString(5, mapper.writeValueAsString(manifest.capabilities()));
            ps.setString(6, manifest.signature() != null
                ? manifest.signature().getOrDefault("publisher", "local").toString()
                : "local");
            ps.executeUpdate();
            log.info("Registered skill: {} v{}", manifest.id(), manifest.version());
        } catch (Exception e) {
            log.error("Failed to record install for skill={}", manifest.id(), e);
        }
    }

    public void removeSkill(String skillId) {
        try (Connection c = getConn()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM skill_registry WHERE id = ?")) {
                ps.setString(1, skillId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM skill_snapshot WHERE skill_id = ?")) {
                ps.setString(1, skillId);
                ps.executeUpdate();
            }
            log.info("Removed skill from registry: {}", skillId);
        } catch (SQLException e) {
            log.error("Failed to remove skill={}", skillId, e);
        }
    }

    public List<Map<String, String>> listSkills() {
        List<Map<String, String>> result = new ArrayList<>();
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT id, version, runtime, abi, source FROM skill_registry");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(Map.of(
                    "id", rs.getString("id"),
                    "version", rs.getString("version"),
                    "runtime", rs.getString("runtime"),
                    "abi", rs.getString("abi"),
                    "source", rs.getString("source")
                ));
            }
        } catch (SQLException e) {
            log.error("Failed to list skills", e);
        }
        return result;
    }

    // ── OpLog ────────────────────────────────────────────────────

    public void recordOpLog(String skillId, String opType, String eventId, String payload) {
        try (PreparedStatement ps = getConn().prepareStatement(
                "INSERT INTO op_log(skill_id, op_type, event_id, payload) VALUES(?, ?, ?, ?)")) {
            ps.setString(1, skillId);
            ps.setString(2, opType);
            ps.setString(3, eventId);
            ps.setString(4, payload);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("op_log insert failed: {}", e.getMessage());
        }
    }

    // ── Capability Audit ─────────────────────────────────────────

    public void recordCapAudit(String skillId, String capName, String method,
                               String argsSummary, String result) {
        try (PreparedStatement ps = getConn().prepareStatement(
                "INSERT INTO capability_audit(skill_id, cap_name, method, args_summary, result) " +
                "VALUES(?, ?, ?, ?, ?)")) {
            ps.setString(1, skillId);
            ps.setString(2, capName);
            ps.setString(3, method);
            ps.setString(4, argsSummary);
            ps.setString(5, result);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("capability_audit insert failed: {}", e.getMessage());
        }
    }

    // ── Snapshot helper ──────────────────────────────────────────

    public Connection getRegistryConnection() throws SQLException {
        return getConn();
    }

    public void ensureTable(String ddl) {
        try (Statement stmt = getConn().createStatement()) {
            stmt.execute(ddl);
        } catch (SQLException e) {
            log.warn("ensureTable failed: {}", e.getMessage());
        }
    }

    // ── Model Routing ──────────────────────────────────────────

    public Map<String, String> getModelRouting(String taskType) {
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT provider, model, api_url, api_key, params FROM model_routing WHERE task_type = ?")) {
            ps.setString(1, taskType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Map.of("provider", rs.getString("provider"),
                                  "model", rs.getString("model"),
                                  "apiUrl", nvl(rs.getString("api_url")),
                                  "apiKey", nvl(rs.getString("api_key")),
                                  "params", nvl(rs.getString("params")));
                }
            }
        } catch (SQLException e) {
            log.warn("getModelRouting failed: {}", e.getMessage());
        }
        return Map.of("provider", "openai", "model", "gpt-4o-mini",
                      "apiUrl", "", "apiKey", "", "params", "{}");
    }

    public void updateModelRouting(String taskType, String provider, String model,
                                    String apiUrl, String apiKey, String params) {
        try (PreparedStatement ps = getConn().prepareStatement(
                "INSERT OR REPLACE INTO model_routing(task_type, provider, model, api_url, api_key, params, updated_at) " +
                "VALUES(?, ?, ?, ?, ?, ?, strftime('%s','now'))")) {
            ps.setString(1, taskType);
            ps.setString(2, provider);
            ps.setString(3, model);
            ps.setString(4, apiUrl != null ? apiUrl : "");
            ps.setString(5, apiKey != null ? apiKey : "");
            ps.setString(6, params != null ? params : "{}");
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("updateModelRouting failed: {}", e.getMessage());
        }
    }

    public Map<String, Map<String, String>> listModelRouting() {
        Map<String, Map<String, String>> result = new java.util.LinkedHashMap<>();
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT task_type, provider, model, api_url, api_key, params FROM model_routing")) {
            while (rs.next()) {
                result.put(rs.getString("task_type"), Map.of(
                    "provider", rs.getString("provider"),
                    "model", rs.getString("model"),
                    "apiUrl", nvl(rs.getString("api_url")),
                    "apiKey", nvl(rs.getString("api_key")),
                    "params", nvl(rs.getString("params"))
                ));
            }
        } catch (SQLException e) {
            log.warn("listModelRouting failed: {}", e.getMessage());
        }
        return result;
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    public void deleteModelRouting(String taskType) {
        try (PreparedStatement ps = getConn().prepareStatement(
                "DELETE FROM model_routing WHERE task_type = ?")) {
            ps.setString(1, taskType);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("deleteModelRouting failed: {}", e.getMessage());
        }
    }

    /** Seed model routing from wuwei.json. Only fills missing task types; never overwrites api_key set via UI. */
    public void seedDefaultRouting(Map<String, String> llmConfig) {
        try {
            String provider = llmConfig.getOrDefault("provider", "deepseek");
            String model = llmConfig.getOrDefault("model", "deepseek-chat");
            String apiUrl = llmConfig.getOrDefault("apiUrl", "");
            String params = llmConfig.getOrDefault("params", "{}");

            // Only update provider/model/apiUrl for rows that were never configured.
            // api_key is only set via the frontend model-routing UI, never from config.
            try (PreparedStatement ps = getConn().prepareStatement(
                    "UPDATE model_routing SET provider=?, model=?, api_url=?, params=?, updated_at=strftime('%s','now') "
                    + "WHERE api_key='' OR api_key IS NULL")) {
                ps.setString(1, provider);
                ps.setString(2, model);
                ps.setString(3, apiUrl);
                ps.setString(4, params);
                int updated = ps.executeUpdate();
                if (updated > 0) {
                    log.info("Updated {} model_routing rows with config from wuwei.json", updated);
                }
            }

            // Insert any missing task types (without api_key, user sets via UI)
            String[] taskTypes = {"skill/generate", "skill/repair", "ai/ask", "skill/drift"};
            for (String tt : taskTypes) {
                try (PreparedStatement ps = getConn().prepareStatement(
                        "INSERT OR IGNORE INTO model_routing(task_type, provider, model, api_url, params, updated_at) "
                        + "VALUES(?, ?, ?, ?, ?, strftime('%s','now'))")) {
                    ps.setString(1, tt);
                    ps.setString(2, provider);
                    ps.setString(3, model);
                    ps.setString(4, apiUrl);
                    ps.setString(5, params);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            log.warn("seedDefaultRouting failed: {}", e.getMessage());
        }
    }

    // ── Chat Memory (langchain4j ChatMemoryStore backend) ──────

    public void saveChatMessages(String skillId, List<Map<String, Object>> messages) {
        try (PreparedStatement ps = getConn().prepareStatement(
                "INSERT OR REPLACE INTO skill_chat_memory(skill_id, msg_index, msg_type, msg_text) " +
                "VALUES(?, ?, ?, ?)")) {
            ps.setString(1, skillId);
            for (int i = 0; i < messages.size(); i++) {
                Map<String, Object> m = messages.get(i);
                ps.setInt(2, i);
                ps.setString(3, (String) m.get("type"));
                ps.setString(4, (String) m.get("text"));
                ps.executeUpdate();
            }
            // Delete stale rows beyond current size
            try (PreparedStatement del = getConn().prepareStatement(
                    "DELETE FROM skill_chat_memory WHERE skill_id = ? AND msg_index >= ?")) {
                del.setString(1, skillId);
                del.setInt(2, messages.size());
                del.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("saveChatMessages failed: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> loadChatMessages(String skillId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT msg_index, msg_type, msg_text FROM skill_chat_memory " +
                "WHERE skill_id = ? ORDER BY msg_index")) {
            ps.setString(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(Map.of(
                        "type", rs.getString("msg_type"),
                        "text", rs.getString("msg_text"),
                        "index", rs.getInt("msg_index")
                    ));
                }
            }
        } catch (SQLException e) {
            log.warn("loadChatMessages failed: {}", e.getMessage());
        }
        return result;
    }

    public void saveMemorySummary(String skillId, String summary, String coveredRange) {
        try (PreparedStatement ps = getConn().prepareStatement(
                "INSERT OR REPLACE INTO skill_memory_summary(skill_id, summary_text, covered_range, generated_at) " +
                "VALUES(?, ?, ?, strftime('%s','now'))")) {
            ps.setString(1, skillId);
            ps.setString(2, summary);
            ps.setString(3, coveredRange);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("saveMemorySummary failed: {}", e.getMessage());
        }
    }

    public String loadMemorySummary(String skillId) {
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT summary_text FROM skill_memory_summary WHERE skill_id = ?")) {
            ps.setString(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("summary_text");
            }
        } catch (SQLException e) {
            log.warn("loadMemorySummary failed: {}", e.getMessage());
        }
        return null;
    }

    public void deleteChatMemory(String skillId) {
        try (Connection c = getConn()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM skill_chat_memory WHERE skill_id = ?")) {
                ps.setString(1, skillId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM skill_memory_summary WHERE skill_id = ?")) {
                ps.setString(1, skillId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("deleteChatMemory failed: {}", e.getMessage());
        }
    }

    public void recordDrift(String skillId, String versionFrom, String versionTo,
                            double driftScore, String retainedGoals, String lostGoals,
                            String newGoals, String reason, String recommendation) {
        try (PreparedStatement ps = getConn().prepareStatement(
                "INSERT INTO skill_drift_log(skill_id, version_from, version_to, " +
                "drift_score, retained_goals, lost_goals, new_goals, reason, recommendation) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, skillId);
            ps.setString(2, versionFrom);
            ps.setString(3, versionTo);
            ps.setDouble(4, driftScore);
            ps.setString(5, retainedGoals);
            ps.setString(6, lostGoals);
            ps.setString(7, newGoals);
            ps.setString(8, reason);
            ps.setString(9, recommendation);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("recordDrift failed: {}", e.getMessage());
        }
    }

    // ── Model Usage Log ────────────────────────────────────────

    public void recordModelUsage(String taskType, String provider, String model,
                                  int inputTokens, int outputTokens,
                                  int latencyMs, double cost) {
        try (PreparedStatement ps = getConn().prepareStatement(
                "INSERT INTO model_usage_log(task_type, provider, model, " +
                "input_tokens, output_tokens, latency_ms, cost) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, taskType);
            ps.setString(2, provider);
            ps.setString(3, model);
            ps.setInt(4, inputTokens);
            ps.setInt(5, outputTokens);
            ps.setInt(6, latencyMs);
            ps.setDouble(7, cost);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("recordModelUsage failed: {}", e.getMessage());
        }
    }
}
