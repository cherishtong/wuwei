package com.wuwei.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.TreeMap;

/**
 * Post-processing normalizer for LLM output.
 * Produces deterministic output: same intent → same files (git-diff friendly).
 */
public class Normalizer {

    private static final Logger log = LoggerFactory.getLogger(Normalizer.class);

    private final ObjectMapper mapper;
    private final ObjectMapper sortedMapper;

    public Normalizer(ObjectMapper mapper) {
        this.mapper = mapper;
        this.sortedMapper = mapper.copy()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    }

    public SkillFiles normalize(SkillFiles raw) {
        return new SkillFiles(
            normalizeJson(raw.skillJson()),
            normalizeUiJson(raw.uiJson()),
            normalizeJs(raw.handlersJs())
        );
    }

    // ── JSON normalization ──────────────────────────────────────

    String normalizeJson(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            String sorted = sortedMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(node);
            // 2-space indent
            return sorted.replace("\t", "  ");
        } catch (Exception e) {
            log.warn("JSON normalize failed: {}", e.getMessage());
            return json;
        }
    }

    String normalizeUiJson(String uiJson) {
        try {
            JsonNode tree = mapper.readTree(uiJson);
            JsonNode normalized = sortChildrenById(tree);
            String result = sortedMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(normalized);
            return result.replace("\t", "  ");
        } catch (Exception e) {
            log.warn("UI JSON normalize failed: {}", e.getMessage());
            return uiJson;
        }
    }

    private JsonNode sortChildrenById(JsonNode node) {
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            ArrayList<JsonNode> items = new ArrayList<>();
            for (JsonNode item : arr) {
                items.add(item);
            }
            // Do NOT sort the top-level components array — order matters for A2UI dependency resolution.
            // Only sort nested arrays that aren't the components array (e.g., children within a component).
            ArrayNode sorted = mapper.createArrayNode();
            for (JsonNode item : items) {
                sorted.add(sortChildrenById(item));
            }
            return sorted;
        } else if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<String> fieldNames = obj.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                sorted.put(key, obj.get(key));
            }
            ObjectNode result = mapper.createObjectNode();
            sorted.forEach((k, v) -> {
                JsonNode processed = sortChildrenById(v);
                // Sort children arrays by id
                if (k.equals("children") && v.isArray()) {
                    processed = sortChildrenById(v);
                }
                result.set(k, processed);
            });
            return result;
        }
        return node;
    }

    // ── JS normalization ────────────────────────────────────────

    String normalizeJs(String js) {
        if (js == null || js.isBlank()) return js;

        // 1. Remove numeric suffixes from identifiers (varName123 → varName)
        js = js.replaceAll("([a-zA-Z_$][a-zA-Z0-9_$]*?)\\d{2,}(?=[^a-zA-Z0-9_$]|$)", "$1");

        // 2. Normalize whitespace (collapse multiple blank lines to max 1)
        js = js.replaceAll("\\n{3,}", "\n\n");

        // 3. Trim trailing whitespace on each line
        js = js.lines()
            .map(String::stripTrailing)
            .collect(java.util.stream.Collectors.joining("\n"));

        // 4. Ensure single trailing newline
        js = js.stripTrailing() + "\n";

        // 5. Remove redundant "// Path: ..." comments that LLMs sometimes generate
        js = js.replaceAll("//\\s*(Path|File|Created|Generated|Author):.*\\n", "");

        return js;
    }
}
