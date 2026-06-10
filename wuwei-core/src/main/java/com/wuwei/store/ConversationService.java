package com.wuwei.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.entity.ConversationEntity;
import com.wuwei.entity.ConversationMessageEntity;
import com.wuwei.repo.ConversationMessageRepo;
import com.wuwei.repo.ConversationRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Persists user-AI conversations using JPA entities.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepo conversationRepo;
    private final ConversationMessageRepo messageRepo;
    private final ObjectMapper mapper;

    public ConversationService(ConversationRepo conversationRepo,
                               ConversationMessageRepo messageRepo,
                               ObjectMapper mapper) {
        this.conversationRepo = conversationRepo;
        this.messageRepo = messageRepo;
        this.mapper = mapper;
    }

    // ── Create ──────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> createConversation(String skillId, String skillName) {
        String id = mid();
        var conv = new ConversationEntity(id, "新对话", skillId, skillName);
        conversationRepo.save(conv);
        log.info("Created conversation: id={} skillId={}", id, skillId);
        return toMap(conv, List.of());
    }

    @Transactional
    public void updateTitle(String convId, String title) {
        conversationRepo.findById(convId).ifPresent(c -> {
            c.setTitle(title);
            c.setUpdatedAt(System.currentTimeMillis() / 1000);
            conversationRepo.save(c);
        });
    }

    @Transactional
    public Map<String, Object> findOrCreateConversation(String skillId, String skillName) {
        String filterSkillId = (skillId != null && !skillId.isEmpty()) ? skillId : "";
        String filterSkillName = (skillName != null && !skillName.isEmpty()) ? skillName : "";

        var existing = conversationRepo.findFirstBySkillIdOrderByCreatedAtAsc(filterSkillId);
        if (existing.isPresent()) {
            var conv = existing.get();
            var messages = loadMessages(conv.getId());
            return toMap(conv, messages);
        }

        return createConversation(
            filterSkillId.isEmpty() ? null : filterSkillId,
            filterSkillName.isEmpty() ? null : filterSkillName);
    }

    // ── Read ────────────────────────────────────────────────────

    public List<Map<String, Object>> listConversations() {
        return conversationRepo.findAll().stream()
            .sorted((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()))
            .map(c -> {
                var map = toMap(c, List.of());
                // Add last message preview
                var msgs = messageRepo.findByConversationIdOrderByMsgOrderAsc(c.getId());
                if (!msgs.isEmpty()) {
                    var last = msgs.get(msgs.size() - 1);
                    map.put("lastMessage", last.getContent());
                }
                return map;
            }).collect(Collectors.toList());
    }

    public Map<String, Object> getConversation(String convId) {
        return conversationRepo.findById(convId)
            .map(c -> toMap(c, loadMessages(convId)))
            .orElse(null);
    }

    public List<Map<String, Object>> getMessages(String convId) {
        return loadMessages(convId);
    }

    private List<Map<String, Object>> loadMessages(String convId) {
        return messageRepo.findByConversationIdOrderByMsgOrderAsc(convId).stream()
            .map(m -> {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("id", m.getId());
                msg.put("role", m.getRole());
                msg.put("content", m.getContent());
                msg.put("time", m.getTime());
                msg.put("seq", m.getMsgOrder());
                if (m.getMeta() != null && !m.getMeta().isEmpty()) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> meta = mapper.readValue(m.getMeta(), Map.class);
                        msg.putAll(meta);
                    } catch (Exception e) {
                        log.debug("Failed to parse meta for msg {}: {}", m.getId(), e.getMessage());
                    }
                }
                return msg;
            }).collect(Collectors.toList());
    }

    // ── Upsert ──────────────────────────────────────────────────

    @Transactional
    public void upsertMessage(String convId, String messageId, String role,
                              String content, String time, Map<String, Object> meta) {
        var conv = conversationRepo.findById(convId).orElse(null);
        if (conv == null) return;

        String metaJson = null;
        if (meta != null && !meta.isEmpty()) {
            try { metaJson = mapper.writeValueAsString(meta); } catch (JsonProcessingException e) {}
        }

        var existing = messageRepo.findById(messageId);
        if (existing.isPresent()) {
            var msg = existing.get();
            msg.setRole(role);
            msg.setContent(content);
            msg.setTime(time);
            msg.setMeta(metaJson);
            messageRepo.save(msg);
        } else {
            int order = messageRepo.findMaxMsgOrder(convId) + 1;
            var msg = new ConversationMessageEntity(messageId, conv, role, content, time, order, metaJson);
            messageRepo.save(msg);
        }

        conv.setUpdatedAt(System.currentTimeMillis() / 1000);
        conversationRepo.save(conv);
    }

    // ── Add Message ─────────────────────────────────────────────

    @Transactional
    public String addMessageWithId(String convId, String role, String content, String time,
                                   Map<String, Object> meta) {
        var conv = conversationRepo.findById(convId).orElse(null);
        if (conv == null) return mid();

        String msgId = mid();
        int order = messageRepo.findMaxMsgOrder(convId) + 1;

        String metaJson = null;
        if (meta != null && !meta.isEmpty()) {
            try { metaJson = mapper.writeValueAsString(meta); } catch (JsonProcessingException e) {}
        }

        var msg = new ConversationMessageEntity(msgId, conv, role, content, time, order, metaJson);
        messageRepo.save(msg);

        conv.setUpdatedAt(System.currentTimeMillis() / 1000);
        conversationRepo.save(conv);

        return msgId;
    }

    @Transactional
    public void deleteMessage(String convId, String messageId) {
        messageRepo.findById(messageId).ifPresent(m -> {
            if (m.getConversation().getId().equals(convId)) {
                messageRepo.delete(m);
            }
        });
    }

    // ── Delete ──────────────────────────────────────────────────

    @Transactional
    public void deleteConversation(String convId) {
        messageRepo.deleteByConversationId(convId);
        conversationRepo.deleteById(convId);
        log.info("Deleted conversation: {}", convId);
    }

    // ── Home conversation ───────────────────────────────────────

    @Transactional
    public Map<String, Object> getHomeConversation() {
        var existing = conversationRepo.findFirstBySkillIdAndSkillNameOrderByCreatedAtAsc("", "");
        if (existing.isPresent()) {
            var conv = existing.get();
            return toMap(conv, loadMessages(conv.getId()));
        }

        // Create new home conversation
        String id = mid();
        long now = System.currentTimeMillis() / 1000;
        var conv = new ConversationEntity(id, "首页对话", "", "");
        conv.setCreatedAt(now);
        conv.setUpdatedAt(now);
        conversationRepo.save(conv);
        return toMap(conv, List.of());
    }

    // ── Active skill tracking ───────────────────────────────────

    @Transactional
    public void setActiveSkill(String convId, String skillId) {
        conversationRepo.findById(convId).ifPresent(c -> {
            c.setActiveSkillId(skillId != null && !skillId.isEmpty() ? skillId : null);
            c.setUpdatedAt(System.currentTimeMillis() / 1000);
            conversationRepo.save(c);
        });
    }

    public String getActiveSkill(String convId) {
        return conversationRepo.findById(convId)
            .map(c -> {
                String val = c.getActiveSkillId();
                return (val != null && !val.isEmpty()) ? val : null;
            }).orElse(null);
    }

    // ── Helpers ─────────────────────────────────────────────────

    private Map<String, Object> toMap(ConversationEntity c, List<Map<String, Object>> messages) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("title", c.getTitle());
        map.put("skillId", nvlOrNull(c.getSkillId()));
        map.put("skillName", nvlOrNull(c.getSkillName()));
        map.put("activeSkillId", nvlOrNull(c.getActiveSkillId()));
        map.put("createdAt", c.getCreatedAt());
        map.put("updatedAt", c.getUpdatedAt());
        map.put("messages", messages);
        return map;
    }

    private String mid() {
        return Long.toString(System.currentTimeMillis(), 36) +
               Long.toString((long) (Math.random() * 0xFFFFFFF), 36);
    }

    private static String nvlOrNull(String s) {
        return (s != null && !s.isEmpty()) ? s : null;
    }
}
