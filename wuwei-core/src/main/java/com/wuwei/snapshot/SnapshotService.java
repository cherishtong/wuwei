package com.wuwei.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.entity.SnapshotEntity;
import com.wuwei.repo.SnapshotRepo;
import com.wuwei.store.SkillStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Snapshot save/restore for crash recovery and hot reload.
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final SnapshotRepo snapshotRepo;
    private final SkillStateStore stateStore;
    private final ObjectMapper mapper;

    public SnapshotService(SnapshotRepo snapshotRepo, SkillStateStore stateStore, ObjectMapper mapper) {
        this.snapshotRepo = snapshotRepo;
        this.stateStore = stateStore;
        this.mapper = mapper;
    }

    @Transactional
    public void save(String skillId, String version, String abiVersion,
                     JsonNode uiTree, String reason) {
        try {
            String stateSummary = String.valueOf(stateStore.keyCount(skillId));
            var entity = snapshotRepo.findById(skillId).orElse(new SnapshotEntity());
            entity.setSkillId(skillId);
            entity.setVersion(version);
            entity.setAbiVersion(abiVersion);
            entity.setSnapshotTime(System.currentTimeMillis() / 1000);
            entity.setReason(reason);
            entity.setUiTreeJson(mapper.writeValueAsString(uiTree));
            entity.setStateSummary(stateSummary);
            snapshotRepo.save(entity);
            log.debug("Snapshot saved for skill={} reason={}", skillId, reason);
        } catch (JsonProcessingException e) {
            log.error("Snapshot save failed for skill={}", skillId, e);
        }
    }

    public Optional<SkillSnapshot> restore(String skillId) {
        return snapshotRepo.findById(skillId).map(e -> {
            try {
                JsonNode uiTree = mapper.readTree(e.getUiTreeJson());
                return new SkillSnapshot(
                    skillId,
                    e.getVersion(),
                    e.getAbiVersion(),
                    e.getSnapshotTime(),
                    e.getReason(),
                    uiTree,
                    e.getStateSummary()
                );
            } catch (JsonProcessingException ex) {
                log.warn("Snapshot restore parse failed for skill={}: {}", skillId, ex.getMessage());
                return null;
            }
        });
    }

    @Transactional
    public void delete(String skillId) {
        snapshotRepo.deleteById(skillId);
    }
}
