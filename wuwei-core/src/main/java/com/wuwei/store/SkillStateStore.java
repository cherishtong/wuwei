package com.wuwei.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-Skill independent SQLite storage with physical isolation.
 * Each Skill gets its own SQLite file. Direct JDBC — no pool needed.
 */
public class SkillStateStore {

    private static final Logger log = LoggerFactory.getLogger(SkillStateStore.class);

    private final String skillsDir;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastAccess = new ConcurrentHashMap<>();

    public SkillStateStore() {
        String home = System.getProperty("user.home");
        this.skillsDir = Paths.get(home, ".wuwei", "skills").toString();
    }

    private Connection getConn(String skillId) throws SQLException {
        return connections.computeIfAbsent(skillId, id -> {
            String dbPath = Paths.get(skillsDir, id, "phenotype", "state.db").toString();
            try {
                Files.createDirectories(Paths.get(dbPath).getParent());
                Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                try (Statement stmt = c.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL");
                    stmt.execute("PRAGMA synchronous=NORMAL");
                    stmt.execute("PRAGMA busy_timeout=3000");
                }
                log.info("Opened state db for skill={} path={}", id, dbPath);
                return c;
            } catch (Exception e) {
                throw new RuntimeException("Failed to open state db for " + id, e);
            }
        });
    }

    public String get(String skillId, String key) {
        lastAccess.put(skillId, Instant.now());
        ensureTable(skillId);
        try (PreparedStatement ps = getConn(skillId).prepareStatement(
                "SELECT value FROM kv WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        } catch (SQLException e) {
            log.warn("get failed [{}] {}: {}", skillId, key, e.getMessage());
            return null;
        }
    }

    public void put(String skillId, String key, String value) {
        lastAccess.put(skillId, Instant.now());
        ensureTable(skillId);
        try (PreparedStatement ps = getConn(skillId).prepareStatement(
                "INSERT OR REPLACE INTO kv(key, value, updated_at) VALUES(?, ?, strftime('%s','now'))")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("put failed [{}] {}: {}", skillId, key, e.getMessage());
        }
    }

    public void delete(String skillId, String key) {
        lastAccess.put(skillId, Instant.now());
        ensureTable(skillId);
        try (PreparedStatement ps = getConn(skillId).prepareStatement(
                "DELETE FROM kv WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("delete failed [{}] {}: {}", skillId, key, e.getMessage());
        }
    }

    public int keyCount(String skillId) {
        ensureTable(skillId);
        try (PreparedStatement ps = getConn(skillId).prepareStatement(
                "SELECT COUNT(*) FROM kv");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    public void removeSkill(String skillId) {
        Connection c = connections.remove(skillId);
        lastAccess.remove(skillId);
        if (c != null) {
            try { c.close(); } catch (SQLException ignored) {}
            log.info("Closed state db for skill={}", skillId);
        }
        try {
            String dbPath = Paths.get(skillsDir, skillId, "phenotype", "state.db").toString();
            Files.deleteIfExists(Paths.get(dbPath));
        } catch (Exception e) {
            log.warn("Failed to delete state.db for skill={}: {}", skillId, e.getMessage());
        }
    }

    public void closeInactiveConnections() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(10));
        connections.entrySet().removeIf(entry -> {
            Instant last = lastAccess.getOrDefault(entry.getKey(), Instant.EPOCH);
            if (last.isBefore(cutoff)) {
                try { entry.getValue().close(); } catch (SQLException ignored) {}
                lastAccess.remove(entry.getKey());
                log.info("Closed inactive db for skill={}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    private void ensureTable(String skillId) {
        try (Statement stmt = getConn(skillId).createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS kv (" +
                " key TEXT PRIMARY KEY," +
                " value TEXT," +
                " updated_at INTEGER DEFAULT (strftime('%s','now'))" +
                ")");
        } catch (SQLException e) {
            log.error("Failed to ensure KV table for skill={}", skillId, e);
        }
    }
}
