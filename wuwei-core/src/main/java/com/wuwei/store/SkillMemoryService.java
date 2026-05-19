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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-skill evolution memory — three files:
 *
 *   memory/intent.lock    — write-once, original intent (plain text)
 *   memory/evolution.jsonl — append-only, 50-line cap + auto-archive
 *   memory/design.md      — ADR-format design decisions, appended
 *
 * Java is the sole data owner. PI reads from request params and returns
 * deltas; this service persists the deltas.
 */
public class SkillMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SkillMemoryService.class);
    private static final int MAX_EVOLUTION_LINES = 50;

    private final ObjectMapper mapper;
    private final Path skillsBaseDir;

    public SkillMemoryService(ObjectMapper mapper) {
        this.mapper = mapper;
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

    private Path evolutionFile(String skillId) {
        return memoryDir(skillId).resolve("evolution.jsonl");
    }

    private Path designFile(String skillId) {
        return memoryDir(skillId).resolve("design.md");
    }

    // ── Intent (write-once) ──────────────────────────────────────

    /**
     * Writes the original intent. Throws if intent.lock already exists.
     */
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

    // ── Evolution log (append-only, capped) ──────────────────────

    /**
     * Appends an evolution entry. Auto-archives when line count exceeds cap.
     */
    public void appendEvolution(String skillId, String type, String trigger, String summary) {
        try {
            Path dir = memoryDir(skillId);
            Files.createDirectories(dir);
            Path file = evolutionFile(skillId);

            Map<String, Object> entry = Map.of(
                "timestamp", Instant.now().toString(),
                "type", type,
                "trigger", trigger != null ? trigger : "",
                "summary", summary
            );
            String line = mapper.writeValueAsString(entry) + "\n";

            Files.writeString(file, line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // Cap check: if over limit, archive oldest entries
            long lineCount = Files.lines(file).count();
            if (lineCount > MAX_EVOLUTION_LINES) {
                archiveEvolution(skillId);
            }
        } catch (IOException e) {
            log.warn("Failed to append evolution for {}: {}", skillId, e.getMessage());
        }
    }

    private void archiveEvolution(String skillId) throws IOException {
        Path file = evolutionFile(skillId);
        List<String> lines = Files.readAllLines(file);
        if (lines.size() <= MAX_EVOLUTION_LINES) return;

        int overflow = lines.size() - MAX_EVOLUTION_LINES;
        Path archiveFile = memoryDir(skillId).resolve(
            "evolution-" + Instant.now().toString().replace(":", "-") + ".jsonl");
        Files.write(archiveFile, lines.subList(0, overflow));
        Files.write(file, lines.subList(overflow, lines.size()));
        log.info("Archived {} evolution entries for {} to {}", overflow, skillId, archiveFile.getFileName());
    }

    public List<Map<String, Object>> readEvolution(String skillId, int lastN) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Path file = evolutionFile(skillId);
            if (!Files.exists(file)) return result;
            List<String> lines = Files.readAllLines(file);
            int start = Math.max(0, lines.size() - lastN);
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = mapper.readValue(line, Map.class);
                result.add(entry);
            }
        } catch (IOException e) {
            log.warn("Failed to read evolution for {}: {}", skillId, e.getMessage());
        }
        return result;
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

    // ── GC ───────────────────────────────────────────────────────

    /**
     * Deletes all memory files for a skill.
     */
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
