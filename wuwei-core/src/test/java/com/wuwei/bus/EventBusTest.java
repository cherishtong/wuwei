package com.wuwei.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuwei.bus.event.KernelEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void kernelReady_typeShouldBeKebabCase() throws Exception {
        var event = new KernelEvent.KernelReady("6.4.0", 49281);
        String json = serialize(event);
        JsonNode node = mapper.readTree(json);

        assertEquals("kernel-ready", node.get("type").asText());
        assertEquals("6.4.0", node.get("version").asText());
        assertEquals(49281, node.get("port").asInt());
    }

    @Test
    void skillActivated_shouldContainUiAndPatches() throws Exception {
        var patches = List.of();
        var ui = mapper.createObjectNode().put("version", "a2ui/1.0");
        var event = new KernelEvent.SkillActivated("test-skill", "测试技能", "thread-1", ui, patches,
            "js", null, java.util.Map.of());
        String json = serialize(event);
        JsonNode node = mapper.readTree(json);

        assertEquals("skill-activated", node.get("type").asText());
        assertEquals("test-skill", node.get("skill-id").asText());
        assertNotNull(node.get("ui"));
    }

    @Test
    void eventAck_shouldSerializeLatencyMs() throws Exception {
        var event = new KernelEvent.EventAck("skill-a", "query-btn", "ok", 42L);
        String json = serialize(event);
        JsonNode node = mapper.readTree(json);

        assertEquals("event-ack", node.get("type").asText());
        assertEquals("skill-a", node.get("skill-id").asText());
        assertEquals("query-btn", node.get("event-id").asText());
        assertEquals("ok", node.get("status").asText());
        assertEquals(42L, node.get("latency-ms").asLong());
    }

    @Test
    void kernelError_shouldHaveAllFields() throws Exception {
        var event = new KernelEvent.KernelError("stock-monitor", "GATE_DENIED", "access denied");
        String json = serialize(event);
        JsonNode node = mapper.readTree(json);

        assertEquals("kernel-error", node.get("type").asText());
        assertEquals("stock-monitor", node.get("skill-id").asText());
        assertEquals("GATE_DENIED", node.get("code").asText());
        assertEquals("access denied", node.get("message").asText());
    }

    @Test
    void skillList_emptyShouldWork() throws Exception {
        var event = new KernelEvent.SkillList(List.of());
        String json = serialize(event);
        JsonNode node = mapper.readTree(json);

        assertEquals("skill-list", node.get("type").asText());
        assertTrue(node.get("skills").isArray());
        assertEquals(0, node.get("skills").size());
    }

    @Test
    void skillList_withItemsShouldSerialize() throws Exception {
        var meta = List.of(
            new KernelEvent.SkillMeta("timer", "番茄钟", "running", "1.0.0"),
            new KernelEvent.SkillMeta("weather", "天气", "stopped", "2.0.0")
        );
        var event = new KernelEvent.SkillList(meta);
        String json = serialize(event);
        JsonNode node = mapper.readTree(json);

        assertEquals("skill-list", node.get("type").asText());
        assertEquals(2, node.get("skills").size());
        assertEquals("timer", node.get("skills").get(0).get("id").asText());
        assertEquals("番茄钟", node.get("skills").get(0).get("name").asText());
        assertEquals("weather", node.get("skills").get(1).get("id").asText());
        assertEquals("天气", node.get("skills").get(1).get("name").asText());
    }

    @Test
    void gateRequest_shouldSerializeReason() throws Exception {
        var event = new KernelEvent.GateRequest("my-skill", "thread-1", "network", "需要查询API");
        String json = serialize(event);
        JsonNode node = mapper.readTree(json);

        assertEquals("gate-request", node.get("type").asText());
        assertEquals("my-skill", node.get("skill-id").asText());
        assertEquals("network", node.get("cap-name").asText());
        assertEquals("需要查询API", node.get("reason").asText());
    }

    @Test
    void planStep_allStatusesShouldWork() throws Exception {
        for (String status : List.of("generating", "done", "error")) {
            var event = new KernelEvent.PlanStep(status, "test step", null);
            String json = serialize(event);
            JsonNode node = mapper.readTree(json);
            assertEquals("plan-step", node.get("type").asText());
            assertEquals(status, node.get("status").asText());
        }
    }

    @Test
    void toKebabCase_shouldConvertCorrectly() {
        assertEquals("kernel-ready", EventBus.toKebabCase("KernelReady"));
        assertEquals("skill-activated", EventBus.toKebabCase("SkillActivated"));
        assertEquals("a2ui-patch", EventBus.toKebabCase("A2uiPatch"));
        assertEquals("event-ack", EventBus.toKebabCase("EventAck"));
        assertEquals("gate-request", EventBus.toKebabCase("GateRequest"));
        assertEquals("guardian-warning", EventBus.toKebabCase("GuardianWarning"));
    }

    private String serialize(KernelEvent event) {
        // Mirrors EventBus.serialize without the broadcast side-effect
        try {
            var map = new java.util.LinkedHashMap<String, Object>();
            map.put("type", EventBus.toKebabCase(event.getClass().getSimpleName()));
            for (var rc : event.getClass().getRecordComponents()) {
                Object value = rc.getAccessor().invoke(event);
                map.put(EventBus.toKebabCase(rc.getName()), value);
            }
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
