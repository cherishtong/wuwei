package com.wuwei.repo;

import com.wuwei.entity.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConversationRepo extends JpaRepository<ConversationEntity, String> {

    Optional<ConversationEntity> findFirstBySkillIdOrderByCreatedAtAsc(String skillId);

    Optional<ConversationEntity> findFirstBySkillIdAndSkillNameOrderByCreatedAtAsc(String skillId, String skillName);
}
