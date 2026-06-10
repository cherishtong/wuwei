package com.wuwei.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "conversation")
public class ConversationEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "title", nullable = false)
    private String title = "新对话";

    @Column(name = "skill_id")
    private String skillId;

    @Column(name = "skill_name")
    private String skillName;

    @Column(name = "active_skill_id")
    private String activeSkillId;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("msgOrder ASC")
    private List<ConversationMessageEntity> messages = new ArrayList<>();

    public ConversationEntity() {}

    public ConversationEntity(String id, String title, String skillId, String skillName) {
        this.id = id;
        this.title = title;
        this.skillId = skillId;
        this.skillName = skillName;
        long now = System.currentTimeMillis() / 1000;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String s) { this.skillId = s; }
    public String getSkillName() { return skillName; }
    public void setSkillName(String n) { this.skillName = n; }
    public String getActiveSkillId() { return activeSkillId; }
    public void setActiveSkillId(String s) { this.activeSkillId = s; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long t) { this.createdAt = t; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long t) { this.updatedAt = t; }
    public List<ConversationMessageEntity> getMessages() { return messages; }
    public void setMessages(List<ConversationMessageEntity> m) { this.messages = m; }
}
