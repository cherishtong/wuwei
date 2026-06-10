package com.wuwei.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "conversation_message")
public class ConversationMessageEntity {

    @Id
    @Column(name = "id")
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private ConversationEntity conversation;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "content", nullable = false, length = 8192)
    private String content;

    @Column(name = "time", nullable = false)
    private String time;

    @Column(name = "msg_order", nullable = false)
    private Integer msgOrder;

    @Column(name = "meta", length = 4096)
    private String meta;

    public ConversationMessageEntity() {}

    public ConversationMessageEntity(String id, ConversationEntity conversation, String role,
                                      String content, String time, Integer msgOrder, String meta) {
        this.id = id;
        this.conversation = conversation;
        this.role = role;
        this.content = content;
        this.time = time;
        this.msgOrder = msgOrder;
        this.meta = meta;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public ConversationEntity getConversation() { return conversation; }
    public void setConversation(ConversationEntity c) { this.conversation = c; }
    public String getRole() { return role; }
    public void setRole(String r) { this.role = r; }
    public String getContent() { return content; }
    public void setContent(String c) { this.content = c; }
    public String getTime() { return time; }
    public void setTime(String t) { this.time = t; }
    public Integer getMsgOrder() { return msgOrder; }
    public void setMsgOrder(Integer o) { this.msgOrder = o; }
    public String getMeta() { return meta; }
    public void setMeta(String m) { this.meta = m; }
}
