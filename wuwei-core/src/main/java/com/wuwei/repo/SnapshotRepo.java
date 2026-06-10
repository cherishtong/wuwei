package com.wuwei.repo;

import com.wuwei.entity.SnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SnapshotRepo extends JpaRepository<SnapshotEntity, String> {
}
