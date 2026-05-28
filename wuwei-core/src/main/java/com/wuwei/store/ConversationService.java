package com.wuwei.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists user-AI conversations (non-skill-scoped chat history).
 * Uses the central registry.db — two tables: conversation + conversation_message.
 */
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final StoreService storeService;
    private final ObjectMapper mapper;

    public ConversationService(StoreService storeService, ObjectMapper mapper) {
        this.storeService = storeService;
        this.mapper = mapper;
        ensureTables();
    }

    private void ensureTables() {
        storeService.ensureTable(
            "CREATE TABLE IF NOT EXISTS conversation (" +
            "  id TEXT PRIMARY KEY," +
            "  title TEXT NOT NULL DEFAULT '新对话'," +
            "  skill_id TEXT," +
            "  skill_name TEXT," +
            "  active_skill_id TEXT," +
            "  created_at INTEGER NOT NULL DEFAULT (strftime('%s','now'))," +
            "  updated_at INTEGER NOT NULL DEFAULT (strftime('%s','now'))" +
            ")"
        );
        // Add active_skill_id column if upgrading from older schema
        try (Statement stmt = conn().createStatement()) {
            stmt.execute("ALTER TABLE conversation ADD COLUMN active_skill_id TEXT");
        } catch (SQLException ignored) {
            // column already exists
        }
        storeService.ensureTable(
            "CREATE TABLE IF NOT EXISTS conversation_message (" +
            "  id TEXT PRIMARY KEY," +
            "  conversation_id TEXT NOT NULL," +
            "  role TEXT NOT NULL," +
            "  content TEXT NOT NULL," +
            "  time TEXT NOT NULL," +
            "  msg_order INTEGER NOT NULL," +
            "  meta TEXT" +
            ")"
        );
        // Add meta column if upgrading from older schema
        try (Statement stmt = conn().createStatement()) {
            stmt.execute("ALTER TABLE conversation_message ADD COLUMN meta TEXT");
        } catch (SQLException ignored) {
            // column already exists
        }
        storeService.ensureTable(
            "CREATE INDEX IF NOT EXISTS idx_conv_msg_conv_id ON conversation_message(conversation_id, msg_order)"
        );
    }

    private Connection conn() throws SQLException {
        return storeService.getRegistryConnection();
    }

    // ── Create ──────────────────────────────────────────────────────

    public Map<String, Object> createConversation(String skillId, String skillName) {
        String id = mid();
        long now = System.currentTimeMillis() / 1000;
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO conversation(id, title, skill_id, skill_name, active_skill_id, created_at, updated_at) " +
                "VALUES(?, '新对话', ?, ?, NULL, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, skillId != null ? skillId : "");
            ps.setString(3, skillName != null ? skillName : "");
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to create conversation", e);
        }
        log.info("Created conversation: id={} skillId={}", id, skillId);
        return toMap(id, "新对话", skillId, skillName, null, now, now, List.of());
    }

    public void updateTitle(String convId, String title) {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE conversation SET title = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, title);
            ps.setLong(2, System.currentTimeMillis() / 1000);
            ps.setString(3, convId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update conversation title: {}", convId, e);
        }
    }

    /** Find existing or create new — one free-chat (skillId=null) + one per skill. */
    public Map<String, Object> findOrCreateConversation(String skillId, String skillName) {
        String filterSkillId = (skillId != null && !skillId.isEmpty()) ? skillId : "";
        String filterSkillName = (skillName != null && !skillName.isEmpty()) ? skillName : "";

        // Try find existing
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT id, title, skill_id, skill_name, active_skill_id, created_at, updated_at " +
                "FROM conversation WHERE skill_id = ? ORDER BY created_at ASC LIMIT 1")) {
            ps.setString(1, filterSkillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String id = rs.getString("id");
                    List<Map<String, Object>> messages = loadMessages(id);
                    log.info("Found existing conversation: id={} skillId={}", id, skillId);
                    return toMap(
                        id,
                        rs.getString("title"),
                        nvl(rs.getString("skill_id")),
                        nvl(rs.getString("skill_name")),
                        nvl(rs.getString("active_skill_id")),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at"),
                        messages
                    );
                }
            }
        } catch (SQLException e) {
            log.error("Failed to find conversation for skillId={}", skillId, e);
        }

        // Not found — create new
        log.info("Creating new conversation for skillId={}", skillId);
        return createConversation(
            filterSkillId.isEmpty() ? null : filterSkillId,
            filterSkillName.isEmpty() ? null : filterSkillName);
    }

    // ── Read ────────────────────────────────────────────────────────

    public List<Map<String, Object>> listConversations() {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Statement stmt = conn().createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT c.id, c.title, c.skill_id, c.skill_name, c.active_skill_id, c.created_at, c.updated_at, " +
                 "  (SELECT m.content FROM conversation_message m " +
                 "   WHERE m.conversation_id = c.id ORDER BY m.msg_order DESC LIMIT 1) AS last_message " +
                 "FROM conversation c ORDER BY c.updated_at DESC")) {
            while (rs.next()) {
                Map<String, Object> item = toMap(
                    rs.getString("id"),
                    rs.getString("title"),
                    nvl(rs.getString("skill_id")),
                    nvl(rs.getString("skill_name")),
                    nvl(rs.getString("active_skill_id")),
                    rs.getLong("created_at"),
                    rs.getLong("updated_at"),
                    List.of() // messages loaded on demand
                );
                String lastMsg = rs.getString("last_message");
                if (lastMsg != null) {
                    item.put("lastMessage", lastMsg);
                }
                result.add(item);
            }
        } catch (SQLException e) {
            log.error("Failed to list conversations", e);
        }
        return result;
    }

    public Map<String, Object> getConversation(String convId) {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT id, title, skill_id, skill_name, active_skill_id, created_at, updated_at " +
                "FROM conversation WHERE id = ?")) {
            ps.setString(1, convId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    List<Map<String, Object>> messages = loadMessages(convId);
                    return toMap(
                        rs.getString("id"),
                        rs.getString("title"),
                        nvl(rs.getString("skill_id")),
                        nvl(rs.getString("skill_name")),
                        nvl(rs.getString("active_skill_id")),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at"),
                        messages
                    );
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get conversation: {}", convId, e);
        }
        return null;
    }

    public List<Map<String, Object>> getMessages(String convId) {
        return loadMessages(convId);
    }

    private List<Map<String, Object>> loadMessages(String convId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT id, role, content, time, msg_order, meta FROM conversation_message " +
                "WHERE conversation_id = ? ORDER BY msg_order")) {
            ps.setString(1, convId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("id", rs.getString("id"));
                    msg.put("role", rs.getString("role"));
                    msg.put("content", rs.getString("content"));
                    msg.put("time", rs.getString("time"));
                    msg.put("seq", rs.getInt("msg_order"));
                    String metaJson = rs.getString("meta");
                    if (metaJson != null && !metaJson.isEmpty()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> meta = mapper.readValue(metaJson, Map.class);
                            msg.putAll(meta);
                        } catch (Exception e) {
                            log.debug("Failed to parse meta for msg {}: {}", rs.getString("id"), e.getMessage());
                        }
                    }
                    result.add(msg);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load messages for conversation: {}", convId, e);
        }
        return result;
    }

    // ── Upsert ───────────────────────────────────────────────────────

    /**
     * Insert or replace a message by ID. Used for messages that update in-place
     * (e.g., streaming text or generation cards whose steps evolve over time).
     *
     * msg_order is preserved on update so the message stays at its original position.
     */
    public void upsertMessage(String convId, String messageId, String role,
                              String content, String time, Map<String, Object> meta) {
        try {
            String metaJson = null;
            if (meta != null && !meta.isEmpty()) {
                try {
                    metaJson = mapper.writeValueAsString(meta);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize meta for upsert: {}", e.getMessage());
                }
            }

            // Check if message already exists
            boolean exists = false;
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT 1 FROM conversation_message WHERE id = ?")) {
                ps.setString(1, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                try (PreparedStatement ps = conn().prepareStatement(
                        "UPDATE conversation_message SET role=?, content=?, time=?, meta=? WHERE id=?")) {
                    ps.setString(1, role);
                    ps.setString(2, content);
                    ps.setString(3, time);
                    ps.setString(4, metaJson);
                    ps.setString(5, messageId);
                    ps.executeUpdate();
                }
            } else {
                int order = 0;
                try (PreparedStatement ps = conn().prepareStatement(
                        "SELECT COALESCE(MAX(msg_order), -1) + 1 FROM conversation_message WHERE conversation_id = ?")) {
                    ps.setString(1, convId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) order = rs.getInt(1);
                    }
                }
                try (PreparedStatement ps = conn().prepareStatement(
                        "INSERT INTO conversation_message(id, conversation_id, role, content, time, msg_order, meta) " +
                        "VALUES(?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, messageId);
                    ps.setString(2, convId);
                    ps.setString(3, role);
                    ps.setString(4, content);
                    ps.setString(5, time);
                    ps.setInt(6, order);
                    ps.setString(7, metaJson);
                    ps.executeUpdate();
                }
            }

            // Update conversation timestamp
            try (PreparedStatement ps = conn().prepareStatement(
                    "UPDATE conversation SET updated_at = strftime('%s','now') WHERE id = ?")) {
                ps.setString(1, convId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("Failed to upsert message {} in conversation {}", messageId, convId, e);
        }
    }

    // ── Update ──────────────────────────────────────────────────────

    /** Add a message with extended metadata stored in the meta JSON column. */
    public String addMessageWithId(String convId, String role, String content, String time,
                                   Map<String, Object> meta) {
        String msgId = mid();
        try {
            int order = 0;
            try (PreparedStatement ps = conn().prepareStatement(
                    "SELECT COALESCE(MAX(msg_order), -1) + 1 FROM conversation_message WHERE conversation_id = ?")) {
                ps.setString(1, convId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) order = rs.getInt(1);
                }
            }

            String metaJson = null;
            if (meta != null && !meta.isEmpty()) {
                try {
                    metaJson = mapper.writeValueAsString(meta);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize meta for message: {}", e.getMessage());
                }
            }
            try (PreparedStatement ps = conn().prepareStatement(
                    "INSERT INTO conversation_message(id, conversation_id, role, content, time, msg_order, meta) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, msgId);
                ps.setString(2, convId);
                ps.setString(3, role);
                ps.setString(4, content);
                ps.setString(5, time);
                ps.setInt(6, order);
                ps.setString(7, metaJson);
                ps.executeUpdate();
            }

            // Update timestamp
            try (PreparedStatement ps = conn().prepareStatement(
                    "UPDATE conversation SET updated_at = strftime('%s','now') WHERE id = ?")) {
                ps.setString(1, convId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("Failed to add message with meta to conversation: {}", convId, e);
        }
        return msgId;
    }

    /** Delete a single message from a conversation. */
    public void deleteMessage(String convId, String messageId) {
        try (PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM conversation_message WHERE id = ? AND conversation_id = ?")) {
            ps.setString(1, messageId);
            ps.setString(2, convId);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.debug("Deleted message {} from conversation {}", messageId, convId);
            }
        } catch (SQLException e) {
            log.error("Failed to delete message {}: {}", messageId, e.getMessage());
        }
    }

    // ── Delete ──────────────────────────────────────────────────────

    public void deleteConversation(String convId) {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM conversation_message WHERE conversation_id = ?")) {
                    ps.setString(1, convId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM conversation WHERE id = ?")) {
                    ps.setString(1, convId);
                    ps.executeUpdate();
                }
                c.commit();
                log.info("Deleted conversation: {}", convId);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to delete conversation: {}", convId, e);
        }
    }

    // ── Home conversation ────────────────────────────────────────────

    /** Get or create the dedicated home conversation. */
    public Map<String, Object> getHomeConversation() {
        // Home conversation has empty skill_id (never confused with chat-dialog convos)
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT id, title, skill_id, skill_name, active_skill_id, created_at, updated_at " +
                "FROM conversation WHERE skill_id = '' AND skill_name = '' " +
                "ORDER BY created_at ASC LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    List<Map<String, Object>> messages = loadMessages(rs.getString("id"));
                    return toMap(
                        rs.getString("id"),
                        rs.getString("title"),
                        nvl(rs.getString("skill_id")),
                        nvl(rs.getString("skill_name")),
                        nvl(rs.getString("active_skill_id")),
                        rs.getLong("created_at"),
                        rs.getLong("updated_at"),
                        messages
                    );
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get home conversation", e);
        }
        // Create new home conversation (empty skill_id and skill_name)
        String id = mid();
        long now = System.currentTimeMillis() / 1000;
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO conversation(id, title, skill_id, skill_name, active_skill_id, created_at, updated_at) " +
                "VALUES(?, '首页对话', '', '', NULL, ?, ?)")) {
            ps.setString(1, id);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to create home conversation", e);
        }
        return toMap(id, "首页对话", "", "", null, now, now, List.of());
    }

    // ── Active skill tracking ────────────────────────────────────────

    /** Record the currently active skill in a thread. */
    public void setActiveSkill(String convId, String skillId) {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE conversation SET active_skill_id = ?, updated_at = strftime('%s','now') WHERE id = ?")) {
            ps.setString(1, skillId != null && !skillId.isEmpty() ? skillId : null);
            ps.setString(2, convId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to set active skill for {}: {}", convId, e.getMessage());
        }
    }

    /** Get the currently active skill in a thread (null if none). */
    public String getActiveSkill(String convId) {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT active_skill_id FROM conversation WHERE id = ?")) {
            ps.setString(1, convId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String val = rs.getString("active_skill_id");
                    return (val != null && !val.isEmpty()) ? val : null;
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get active skill for {}: {}", convId, e.getMessage());
        }
        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private Map<String, Object> toMap(String id, String title, String skillId, String skillName,
                                       String activeSkillId,
                                       long createdAt, long updatedAt, List<Map<String, Object>> messages) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("title", title);
        map.put("skillId", skillId != null && !skillId.isEmpty() ? skillId : null);
        map.put("skillName", skillName != null && !skillName.isEmpty() ? skillName : null);
        map.put("activeSkillId", activeSkillId != null && !activeSkillId.isEmpty() ? activeSkillId : null);
        map.put("createdAt", createdAt);
        map.put("updatedAt", updatedAt);
        map.put("messages", messages);
        return map;
    }

    private String mid() {
        return Long.toString(System.currentTimeMillis(), 36) +
               Long.toString((long) (Math.random() * 0xFFFFFFF), 36);
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
