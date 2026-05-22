package com.wuwei.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-skill immutable memory — two files:
 *
 *   memory/intent.lock  — write-once, original intent (constitution)
 *   memory/design.md    — ADR-format design decisions, appended (case law)
 *
 * Conversation-level memory (evolution) is handled by {@link SummarizingChatMemoryStore}
 * via langchain4j {@code ChatMemory} — this class only owns the immutable artifacts.
 */
public class SkillMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SkillMemoryService.class);

    private final Path skillsBaseDir;

    public SkillMemoryService(ObjectMapper mapper) {
        String home = System.getProperty("user.home");
        this.skillsBaseDir = Paths.get(home, ".wuwei", "skills");
    }

    // ── Paths ────────────────────────────────────────────────────

    private Path memoryDir(String skillId) {
        return skillsBaseDir.resolve(skillId).resolve("memory");
    }

    private Path intentFile(String skillId) {
        return memoryDir(skillId).resolve("intent.lock");
    }

    private Path designFile(String skillId) {
        return memoryDir(skillId).resolve("design.md");
    }

    // ── Intent (write-once, immutable) ───────────────────────────

    public void writeIntent(String skillId, String intent) throws IOException {
        Path dir = memoryDir(skillId);
        Files.createDirectories(dir);
        Path file = intentFile(skillId);
        if (Files.exists(file)) {
            throw new IOException("intent.lock already exists — intent is immutable");
        }
        Files.writeString(file, intent.trim() + "\n");
        log.info("Intent locked for {}", skillId);
    }

    public String readIntent(String skillId) {
        try {
            Path file = intentFile(skillId);
            if (Files.exists(file)) return Files.readString(file).trim();
        } catch (IOException e) {
            log.warn("Failed to read intent.lock for {}: {}", skillId, e.getMessage());
        }
        return null;
    }

    // ── Design log (ADR-format, append) ──────────────────────────

    public void appendDesign(String skillId, String title, String decision) {
        try {
            Path dir = memoryDir(skillId);
            Files.createDirectories(dir);
            Path file = designFile(skillId);

            String timestamp = Instant.now().toString();
            String entry = String.format("""
                ---
                title: %s
                date: %s
                ---
                %s

                """, title, timestamp, decision);

            Files.writeString(file, entry,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to append design for {}: {}", skillId, e.getMessage());
        }
    }

    public String readDesign(String skillId) {
        try {
            Path file = designFile(skillId);
            if (Files.exists(file)) return Files.readString(file);
        } catch (IOException e) {
            log.warn("Failed to read design.md for {}: {}", skillId, e.getMessage());
        }
        return null;
    }

    // ── Memory context for prompt injection ──────────────────────

    /**
     * Returns the immutable memory context to inject into the LLM prompt.
     * Conversation history is handled separately by ChatMemory via {@code @MemoryId}.
     */
    public Map<String, Object> getMemoryContext(String skillId) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        String intent = readIntent(skillId);
        if (intent != null) ctx.put("originalIntent", intent);
        String design = readDesign(skillId);
        if (design != null) ctx.put("recentDesigns", design);
        return ctx;
    }

    // ── GC ───────────────────────────────────────────────────────

    /** Deletes all memory files for a skill. */
    public void deleteAll(String skillId) {
        try {
            Path dir = memoryDir(skillId);
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to delete memory for {}: {}", skillId, e.getMessage());
        }
    }
}
