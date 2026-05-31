package com.wuwei.a2ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks A2UI component state per skill and applies component-level patches.
 *
 * In the new architecture, the UI tree is a flat components array:
 *   [{id: "root", component: "Column", children: [...]}, ...]
 * Patches are A2UI-native component fragments:
 *   {id: "count-display", text: {literalString: "5"}}
 */
public class A2uiEngine {

    private static final Logger log = LoggerFactory.getLogger(A2uiEngine.class);

    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, JsonNode> trees = new ConcurrentHashMap<>();

    public A2uiEngine(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Resolve $ref fragments in a UI tree from a ui/ directory.
     * Walks the entire JSON tree, replacing {"$ref": "./components/form.json#Form"}
     * with the actual subtree from the referenced file + key.
     */
    public JsonNode resolveUiRefs(Path uiDir) throws IOException {
        Path indexJson = uiDir.resolve("index.json");
        if (!Files.exists(indexJson)) {
            throw new IOException("ui/index.json not found in " + uiDir);
        }
        JsonNode tree = mapper.readTree(indexJson.toFile());
        return resolveRefs(tree, uiDir, new HashSet<>(), 0);
    }

    private JsonNode resolveRefs(JsonNode node, Path uiDir, Set<String> stack, int depth)
            throws IOException {
        if (depth > 10) {
            throw new IOException("UI $ref depth limit exceeded (circular reference?)");
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (obj.has("$ref") && obj.size() == 1) {
                String ref = obj.get("$ref").asText();
                // Format: "./path/to/file.json#Key"
                int hash = ref.indexOf('#');
                if (hash == -1) {
                    throw new IOException("Invalid $ref format (missing #Key): " + ref);
                }
                String filePath = ref.substring(0, hash);
                String key = ref.substring(hash + 1);
                Path resolvedPath = uiDir.resolve(filePath).normalize();
                if (!resolvedPath.startsWith(uiDir)) {
                    throw new IOException("$ref path traversal denied: " + filePath);
                }
                if (!Files.exists(resolvedPath)) {
                    throw new IOException("$ref target not found: " + filePath);
                }
                String refKey = resolvedPath.toString() + "#" + key;
                if (!stack.add(refKey)) {
                    throw new IOException("Circular $ref detected: " + refKey);
                }
                JsonNode fragment = mapper.readTree(resolvedPath.toFile());
                if (!fragment.has(key)) {
                    throw new IOException("$ref key '" + key + "' not found in " + filePath);
                }
                JsonNode resolvedNode = resolveRefs(fragment.get(key), uiDir, stack, depth + 1);
                stack.remove(refKey);
                return resolvedNode;
            }
            // Recursively process all fields
            ObjectNode result = mapper.createObjectNode();
            var iter = obj.fields();
            while (iter.hasNext()) {
                var entry = iter.next();
                result.set(entry.getKey(), resolveRefs(entry.getValue(), uiDir, stack, depth));
            }
            return result;
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            ArrayNode result = mapper.createArrayNode();
            for (JsonNode item : arr) {
                result.add(resolveRefs(item, uiDir, stack, depth));
            }
            return result;
        }
        return node;
    }

    public void register(String skillId, JsonNode uiTree) {
        // Don't overwrite a valid tree with an empty one (file watcher race condition)
        if (uiTree == null || !uiTree.has("components") || uiTree.get("components").size() == 0) {
            JsonNode existing = trees.get(skillId);
            if (existing != null && existing.has("components") && existing.get("components").size() > 0) {
                log.info("A2uiEngine skip empty overwrite for: {}", skillId);
                return;
            }
        }
        trees.put(skillId, uiTree);
        log.info("A2uiEngine registered: {}", skillId);
    }

    public JsonNode getTree(String skillId) {
        return trees.get(skillId);
    }

    public void unregister(String skillId) {
        trees.remove(skillId);
        log.info("A2uiEngine unregistered: {}", skillId);
    }

    /**
     * Apply A2UI-native component patches to the skill's component state.
     * Each patch is a component fragment: {id: "x", text: {literalString: "..."}}
     * The matching component in the stored array is updated in-place.
     * Returns the list of patches (all pass through to frontend).
     */
    /**
     * Apply component patches to the skill's component state.
     * Each patch is a component fragment: {id: "x", text: "new text"}
     * The matching component in the stored array is updated in-place.
     * Returns the FULL merged components (with component type preserved) for frontend broadcast.
     */
    @SuppressWarnings("unchecked")
    public List<Object> applyPatches(String skillId, List<Object> patches) {
        JsonNode tree = trees.get(skillId);
        if (tree == null) {
            log.warn("A2uiEngine: no tree for {}", skillId);
            return List.of();
        }

        JsonNode componentsNode = null;
        if (tree.has("components")) {
            componentsNode = tree.get("components");
        }

        List<Object> fullComponents = new ArrayList<>();

        for (Object p : patches) {
            if (!(p instanceof Map<?, ?> raw)) {
                fullComponents.add(p); // pass through non-map patches as-is
                continue;
            }
            String id = raw.containsKey("id") ? java.util.Objects.toString(raw.get("id"), "") : "";

            // Data patches (type: "data") pass through unchanged
            if (id.isEmpty()) {
                fullComponents.add(raw);
                continue;
            }

            // Find and update the matching component
            boolean found = false;
            if (componentsNode != null && componentsNode.isArray()) {
                ArrayNode arr = (ArrayNode) componentsNode;
                for (int i = 0; i < arr.size(); i++) {
                    JsonNode comp = arr.get(i);
                    if (comp.has("id") && id.equals(comp.get("id").asText())
                        && comp instanceof ObjectNode objNode) {
                        // Update all properties from the patch (except "id")
                        for (var entry : raw.entrySet()) {
                            String key = java.util.Objects.toString(entry.getKey(), "");
                            if ("id".equals(key) || key.isEmpty()) continue;
                            JsonNode valueNode = convertValue(entry.getValue());
                            objNode.set(key, valueNode);
                        }
                        // Return the full merged component so frontend has component type
                        fullComponents.add(mapper.convertValue(objNode, Map.class));
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                fullComponents.add(raw); // pass through if no matching component
            }
        }

        return fullComponents;
    }

    private JsonNode convertValue(Object value) {
        if (value == null) return mapper.nullNode();
        if (value instanceof Boolean b) return mapper.getNodeFactory().booleanNode(b);
        if (value instanceof Integer n) return mapper.getNodeFactory().numberNode(n.intValue());
        if (value instanceof Long n) return mapper.getNodeFactory().numberNode(n.longValue());
        if (value instanceof Double n) return mapper.getNodeFactory().numberNode(n.doubleValue());
        if (value instanceof Float n) return mapper.getNodeFactory().numberNode(n.floatValue());
        if (value instanceof Number n) return mapper.getNodeFactory().numberNode(n.intValue());
        if (value instanceof String s) return mapper.getNodeFactory().textNode(s);
        if (value instanceof Map<?, ?> m) {
            ObjectNode obj = mapper.createObjectNode();
            for (var entry : m.entrySet()) {
                obj.set(entry.getKey().toString(), convertValue(entry.getValue()));
            }
            return obj;
        }
        return mapper.getNodeFactory().textNode(value.toString());
    }

    /**
     * Serialize the full component state for broadcasting to the frontend.
     */
    public String toJson(String skillId) {
        JsonNode tree = trees.get(skillId);
        if (tree == null) return "{}";
        try {
            return mapper.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            log.error("A2uiEngine serialize error for {}", skillId, e);
            return "{}";
        }
    }
}
