package com.wuwei.repo;

import com.wuwei.entity.SkillRegistryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SkillRegistryRepo extends JpaRepository<SkillRegistryEntity, String> {
}
