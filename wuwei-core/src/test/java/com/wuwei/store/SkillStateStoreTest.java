package com.wuwei.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class SkillStateStoreTest {

    private SkillStateStore store;
    private final String skillA = "test-skill-a";
    private final String skillB = "test-skill-b";

    @BeforeEach
    void setUp() {
        store = new SkillStateStore();
    }

    @AfterEach
    void tearDown() {
        store.removeSkill(skillA);
        store.removeSkill(skillB);
    }

    // ── Basic CRUD ──────────────────────────────────────────────

    @Test
    void putAndGet_shouldPersistValue() {
        store.put(skillA, "name", "test-value");
        assertEquals("test-value", store.get(skillA, "name"));
    }

    @Test
    void get_nonExistentKey_shouldReturnNull() {
        assertNull(store.get(skillA, "no-such-key"));
    }

    @Test
    void put_shouldOverwriteExistingValue() {
        store.put(skillA, "key", "old");
        store.put(skillA, "key", "new");
        assertEquals("new", store.get(skillA, "key"));
    }

    @Test
    void delete_shouldRemoveValue() {
        store.put(skillA, "key", "value");
        store.delete(skillA, "key");
        assertNull(store.get(skillA, "key"));
    }

    @Test
    void delete_nonExistentKey_shouldNotThrow() {
        assertDoesNotThrow(() -> store.delete(skillA, "no-such"));
    }

    @Test
    void keyCount_shouldReturnCorrectCount() {
        store.put(skillA, "a", "1");
        store.put(skillA, "b", "2");
        store.put(skillA, "c", "3");
        assertEquals(3, store.keyCount(skillA));

        store.delete(skillA, "b");
        assertEquals(2, store.keyCount(skillA));
    }

    // ── Physical Isolation ──────────────────────────────────────

    @Test
    void differentSkills_shouldHaveIsolatedStorage() {
        store.put(skillA, "shared-key", "value-a");
        store.put(skillB, "shared-key", "value-b");

        assertEquals("value-a", store.get(skillA, "shared-key"));
        assertEquals("value-b", store.get(skillB, "shared-key"));

        store.delete(skillA, "shared-key");
        assertEquals("value-b", store.get(skillB, "shared-key"));
        assertNull(store.get(skillA, "shared-key"));
    }

    @Test
    void removeSkill_shouldCleanUpPool() {
        store.put(skillA, "x", "1");
        store.removeSkill(skillA);
        // After removal, getting again creates a fresh pool with empty state
        assertNull(store.get(skillA, "x"));
    }

    // ── Concurrency ─────────────────────────────────────────────

    @Test
    void concurrentReadWrite_shouldNotDeadlock() throws Exception {
        int threads = 10;
        int opsPerThread = 100;
        var latch = new CountDownLatch(threads);
        var errors = new ArrayList<Exception>();
        var executor = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        String key = "key-" + threadId + "-" + i;
                        store.put(skillA, key, "val-" + i);
                        String val = store.get(skillA, key);
                        assertEquals("val-" + i, val);
                    }
                } catch (Exception e) {
                    synchronized (errors) { errors.add(e); }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        assertTrue(errors.isEmpty(), "Concurrent errors: " + errors);
        assertEquals(threads * opsPerThread, store.keyCount(skillA));
    }

    @Test
    void concurrentMultipleSkills_shouldNotInterfere() throws Exception {
        int threads = 5;
        var latch = new CountDownLatch(threads * 2);
        var errors = new ArrayList<Exception>();
        var executor = Executors.newFixedThreadPool(threads * 2);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        store.put(skillA, "a-" + tid + "-" + i, String.valueOf(i));
                        store.get(skillA, "a-" + tid + "-" + i);
                    }
                } catch (Exception e) {
                    synchronized (errors) { errors.add(e); }
                } finally { latch.countDown(); }
            });
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        store.put(skillB, "b-" + tid + "-" + i, String.valueOf(i));
                        store.get(skillB, "b-" + tid + "-" + i);
                    }
                } catch (Exception e) {
                    synchronized (errors) { errors.add(e); }
                } finally { latch.countDown(); }
            });
        }

        latch.await();
        executor.shutdown();
        assertTrue(errors.isEmpty(), "Concurrent errors: " + errors);
    }
}
