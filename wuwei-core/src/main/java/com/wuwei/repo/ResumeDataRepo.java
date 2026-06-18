package com.wuwei.repo;

import com.wuwei.entity.ResumeDataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ResumeDataRepo extends JpaRepository<ResumeDataEntity, String> {
}
