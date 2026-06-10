package com.wuwei.entity;

import jakarta.persistence.*;
import java.io.Serializable;

@Entity
@Table(name = "skill_chat_memory")
@IdClass(ChatMemoryEntity.ChatMemoryId.class)
public class ChatMemoryEntity {

    @Id
    @Column(name = "skill_id")
    private String skillId;

    @Id
    @Column(name = "msg_index")
    private Integer msgIndex;

    @Column(name = "msg_type", nullable = false)
    private String msgType;

    @Column(name = "msg_text", nullable = false, length = 8192)
    private String msgText;

    @Column(name = "created_at")
    private Long createdAt;

    public ChatMemoryEntity() {}

    public ChatMemoryEntity(String skillId, Integer msgIndex, String msgType, String msgText) {
        this.skillId = skillId;
        this.msgIndex = msgIndex;
        this.msgType = msgType;
        this.msgText = msgText;
        this.createdAt = System.currentTimeMillis() / 1000;
    }

    public String getSkillId() { return skillId; }
    public void setSkillId(String s) { this.skillId = s; }
    public Integer getMsgIndex() { return msgIndex; }
    public void setMsgIndex(Integer i) { this.msgIndex = i; }
    public String getMsgType() { return msgType; }
    public void setMsgType(String t) { this.msgType = t; }
    public String getMsgText() { return msgText; }
    public void setMsgText(String t) { this.msgText = t; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long t) { this.createdAt = t; }

    public static class ChatMemoryId implements Serializable {
        private String skillId;
        private Integer msgIndex;
        public ChatMemoryId() {}
        public ChatMemoryId(String skillId, Integer msgIndex) { this.skillId = skillId; this.msgIndex = msgIndex; }
        public String getSkillId() { return skillId; }
        public void setSkillId(String s) { this.skillId = s; }
        public Integer getMsgIndex() { return msgIndex; }
        public void setMsgIndex(Integer i) { this.msgIndex = i; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof ChatMemoryId that)) return false;
            return skillId.equals(that.skillId) && msgIndex.equals(that.msgIndex);
        }
        @Override public int hashCode() { return skillId.hashCode() * 31 + msgIndex.hashCode(); }
    }
}
