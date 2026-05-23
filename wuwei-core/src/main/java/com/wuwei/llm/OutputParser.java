package com.wuwei.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses LLM output in the three-file format with === marker === delimiters.
 * Ported from wuwei-pi/src/utils.ts.
 */
public class OutputParser {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Extract the three files from LLM raw output.
     * Expects: === skill.json === ... === ui.json === ... === handlers.js === ...
     */
    public static SkillFiles parseThreeFiles(String raw) throws LlmException {
        String skillJson = extract(raw, "=== skill.json ===", "=== ui.json ===");
        String uiJson = extract(raw, "=== ui.json ===", "=== handlers.js ===");
        // handlers.js is the last block — extract from its marker to end
        int handlersStart = raw.indexOf("=== handlers.js ===");
        if (handlersStart == -1) {
            // Try === genome/handlers.js === as fallback (legacy TS format)
            handlersStart = raw.indexOf("=== genome/handlers.js ===");
            if (handlersStart == -1) {
                throw new LlmException(-32003, "Missing delimiter: === handlers.js ===");
            }
            // Also try genome/ prefix for ui.json
            if (uiJson == null) {
                uiJson = extract(raw, "=== genome/ui.json ===", "=== genome/handlers.js ===");
            }
        }
        // handlers.js may be followed by === design-decision ===; stop there if present
        int designStart = raw.indexOf("=== design-decision ===", handlersStart);
        String handlersJs = (designStart != -1
            ? raw.substring(handlersStart + "=== handlers.js ===".length(), designStart)
            : raw.substring(handlersStart + "=== handlers.js ===".length())).trim();
        handlersJs = stripMarkdownFences(handlersJs);

        // Validate
        validateFilesFormat(new SkillFiles(skillJson, uiJson, handlersJs));
        return new SkillFiles(skillJson, uiJson, handlersJs);
    }

    private static String extract(String raw, String startTag, String endTag) throws LlmException {
        int start = raw.indexOf(startTag);
        if (start == -1) {
            throw new LlmException(-32003, "Missing delimiter: " + startTag);
        }
        int begin = start + startTag.length();
        int end = endTag != null ? raw.indexOf(endTag, begin) : raw.length();
        if (end == -1 && endTag != null) {
            throw new LlmException(-32003, "Missing delimiter: " + endTag);
        }
        String content = raw.substring(begin, end != -1 ? end : raw.length());
        return stripMarkdownFences(content).trim();
    }

    private static String stripMarkdownFences(String content) {
        return content
            .replaceFirst("^```(?:json|js)?\\s*\\n?", "")
            .replaceFirst("\\n?```\\s*$", "");
    }

    /**
     * Validate that the three files are well-formed.
     * skillJson and uiJson must be valid JSON, handlersJs must contain export function or function.
     */
    public static void validateFilesFormat(SkillFiles files) throws LlmException {
        // Validate skill.json is parseable JSON
        try {
            mapper.readTree(files.skillJson());
        } catch (Exception e) {
            throw new LlmException(-32003, "Invalid skill.json: " + e.getMessage());
        }

        // Validate ui.json is parseable JSON
        try {
            mapper.readTree(files.uiJson());
        } catch (Exception e) {
            throw new LlmException(-32003, "Invalid ui.json: " + e.getMessage());
        }

        // Validate handlers.js has at least one function or arrow function
        String js = files.handlersJs();
        if (js == null || js.isBlank()) {
            throw new LlmException(-32003, "handlers.js is empty");
        }
        if (!js.contains("function ") && !js.contains("function(")
            && !js.contains("=>") && !js.contains("=> {")) {
            throw new LlmException(-32003, "handlers.js contains no function or handler");
        }
    }

    /**
     * Extract core goals from LLM output (after === core-goals === marker).
     */
    public static List<String> extractCoreGoals(String raw) {
        List<String> goals = new ArrayList<>();
        int idx = raw.indexOf("=== core-goals ===");
        if (idx == -1) return goals;
        int start = idx + "=== core-goals ===".length();
        int end = raw.indexOf("===", start);
        String section = (end != -1 ? raw.substring(start, end) : raw.substring(start)).trim();
        for (String line : section.split("\\n")) {
            line = line.replaceAll("^[-*]\\s*", "").trim();
            if (!line.isBlank()) goals.add(line);
        }
        return goals;
    }

    /**
     * Extract design decision from LLM output (after === design-decision === marker).
     */
    public static String extractDesignDecision(String raw) {
        int idx = raw.indexOf("=== design-decision ===");
        if (idx == -1) return null;
        int start = idx + "=== design-decision ===".length();
        int end = raw.indexOf("===", start);
        String section = (end != -1 ? raw.substring(start, end) : raw.substring(start)).trim();
        return section.isBlank() ? null : section;
    }
}
