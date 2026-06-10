package com.wuwei.repo;

import com.wuwei.entity.ChatMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMemoryRepo extends JpaRepository<ChatMemoryEntity, ChatMemoryEntity.ChatMemoryId> {

    List<ChatMemoryEntity> findBySkillIdOrderByMsgIndexAsc(String skillId);

    @Modifying
    @Query("DELETE FROM ChatMemoryEntity c WHERE c.skillId = ?1 AND c.msgIndex >= ?2")
    void deleteStaleMessages(String skillId, Integer fromIndex);

    @Modifying
    @Query("DELETE FROM ChatMemoryEntity c WHERE c.skillId = ?1")
    void deleteBySkillId(String skillId);
}
