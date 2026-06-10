package com.wuwei.repo;

import com.wuwei.entity.ConversationMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationMessageRepo extends JpaRepository<ConversationMessageEntity, String> {

    List<ConversationMessageEntity> findByConversationIdOrderByMsgOrderAsc(String conversationId);

    @Query("SELECT COALESCE(MAX(m.msgOrder), 0) FROM ConversationMessageEntity m WHERE m.conversation.id = ?1")
    Integer findMaxMsgOrder(String conversationId);

    @Modifying
    @Query("DELETE FROM ConversationMessageEntity m WHERE m.conversation.id = ?1")
    void deleteByConversationId(String conversationId);
}
