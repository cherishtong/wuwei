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
}
