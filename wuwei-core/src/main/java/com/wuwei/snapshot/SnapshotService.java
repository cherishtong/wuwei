package com.wuwei.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.store.SkillStateStore;
import com.wuwei.store.StoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Snapshot save/restore for crash recovery and hot reload.
 */
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final StoreService store;
    private final SkillStateStore stateStore;
    private final ObjectMapper mapper;

    private static final String SNAPSHOT_TABLE_DDL =
        "CREATE TABLE IF NOT EXISTS skill_snapshot (" +
        " skill_id TEXT PRIMARY KEY," +
        " version TEXT NOT NULL," +
        " abi_version TEXT NOT NULL DEFAULT '1.0'," +
        " snapshot_time INTEGER NOT NULL," +
        " reason TEXT," +
        " ui_tree_json TEXT NOT NULL," +
        " state_summary TEXT" +
        ")";

    public SnapshotService(StoreService store, SkillStateStore stateStore, ObjectMapper mapper) {
        this.store = store;
        this.stateStore = stateStore;
        this.mapper = mapper;
        store.ensureTable(SNAPSHOT_TABLE_DDL);
    }

    public void save(String skillId, String version, String abiVersion,
                     JsonNode uiTree, String reason) {
        try (Connection conn = store.getRegistryConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT OR REPLACE INTO skill_snapshot(skill_id, version, abi_version, " +
                 "snapshot_time, reason, ui_tree_json, state_summary) " +
                 "VALUES(?, ?, ?, ?, ?, ?, ?)")) {

            // Collect all keys from the skill's KV store
            String stateSummary = buildStateSummary(skillId);

            ps.setString(1, skillId);
            ps.setString(2, version);
            ps.setString(3, abiVersion);
            ps.setLong(4, System.currentTimeMillis() / 1000);
            ps.setString(5, reason);
            ps.setString(6, mapper.writeValueAsString(uiTree));
            ps.setString(7, stateSummary);
            ps.executeUpdate();

            log.debug("Snapshot saved for skill={} reason={}", skillId, reason);
        } catch (Exception e) {
            log.error("Snapshot save failed for skill={}", skillId, e);
        }
    }

    public Optional<SkillSnapshot> restore(String skillId) {
        try (Connection conn = store.getRegistryConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT version, abi_version, snapshot_time, reason, ui_tree_json, state_summary " +
                 "FROM skill_snapshot WHERE skill_id = ?")) {
            ps.setString(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    JsonNode uiTree = mapper.readTree(rs.getString("ui_tree_json"));
                    return Optional.of(new SkillSnapshot(
                        skillId,
                        rs.getString("version"),
                        rs.getString("abi_version"),
                        rs.getLong("snapshot_time"),
                        rs.getString("reason"),
                        uiTree,
                        rs.getString("state_summary")
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Snapshot restore failed for skill={}: {}", skillId, e.getMessage());
        }
        return Optional.empty();
    }

    public void delete(String skillId) {
        try (Connection conn = store.getRegistryConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM skill_snapshot WHERE skill_id = ?")) {
            ps.setString(1, skillId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Snapshot delete failed for skill={}", skillId, e);
        }
    }

    private String buildStateSummary(String skillId) {
        // Phase 1: just store key count. Phase 2 will store actual key list.
        int count = stateStore.keyCount(skillId);
        return String.valueOf(count);
    }
}
