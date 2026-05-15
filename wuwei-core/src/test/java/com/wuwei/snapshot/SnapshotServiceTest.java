package com.wuwei.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wuwei.store.SkillStateStore;
import com.wuwei.store.StoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotServiceTest {

    private StoreService storeService;
    private SkillStateStore stateStore;
    private ObjectMapper mapper;
    private SnapshotService snapshotService;
    private final String skillId = "snap-test-skill";

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        storeService = new StoreService(mapper);
        storeService.initSchema();
        stateStore = new SkillStateStore();
        snapshotService = new SnapshotService(storeService, stateStore, mapper);
    }

    @AfterEach
    void tearDown() {
        snapshotService.delete(skillId);
        stateStore.removeSkill(skillId);
    }

    @Test
    void saveAndRestore_shouldPreserveSnapshotData() {
        stateStore.put(skillId, "last-query", "test-query");
        stateStore.put(skillId, "preferences", "dark-mode");

        ObjectNode uiTree = mapper.createObjectNode();
        uiTree.put("version", "a2ui/1.0");
        uiTree.putObject("root").put("type", "container");

        snapshotService.save(skillId, "1.0.0", "1.0", uiTree, "test");

        Optional<SkillSnapshot> restored = snapshotService.restore(skillId);

        assertTrue(restored.isPresent());
        SkillSnapshot snap = restored.get();
        assertEquals(skillId, snap.skillId());
        assertEquals("1.0.0", snap.version());
        assertEquals("1.0", snap.abiVersion());
        assertEquals("test", snap.reason());
        assertNotNull(snap.uiTree());
        assertEquals("a2ui/1.0", snap.uiTree().get("version").asText());
    }

    @Test
    void restore_nonExistentSnapshot_shouldReturnEmpty() {
        Optional<SkillSnapshot> result = snapshotService.restore("non-existent");
        assertFalse(result.isPresent());
    }

    @Test
    void save_overwritesExistingSnapshot() {
        ObjectNode ui1 = mapper.createObjectNode();
        ui1.put("version", "v1");
        snapshotService.save(skillId, "1.0.0", "1.0", ui1, "first");

        ObjectNode ui2 = mapper.createObjectNode();
        ui2.put("version", "v2");
        snapshotService.save(skillId, "1.0.0", "1.0", ui2, "second");

        Optional<SkillSnapshot> restored = snapshotService.restore(skillId);
        assertTrue(restored.isPresent());
        assertEquals("second", restored.get().reason());
    }

    @Test
    void delete_shouldRemoveSnapshot() {
        ObjectNode ui = mapper.createObjectNode();
        snapshotService.save(skillId, "1.0.0", "1.0", ui, "test");
        assertTrue(snapshotService.restore(skillId).isPresent());

        snapshotService.delete(skillId);
        assertFalse(snapshotService.restore(skillId).isPresent());
    }
}
