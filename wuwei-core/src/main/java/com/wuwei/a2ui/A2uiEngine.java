package com.wuwei.a2ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    public void register(String skillId, JsonNode uiTree) {
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
