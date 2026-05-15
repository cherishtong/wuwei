package com.wuwei.skill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Parsed skill.json manifest. Immutable after construction.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillManifest(
    @JsonProperty("id") String id,
    @JsonProperty("version") String version,
    @JsonProperty("abi") String abi,
    @JsonProperty("runtime") String runtime,
    @JsonProperty("meta") Map<String, Object> meta,
    @JsonProperty("capabilities") Map<String, Object> capabilities,
    @JsonProperty("signature") Map<String, Object> signature
) {
    public boolean hasCapability(String name) {
        return capabilities != null && capabilities.containsKey(name);
    }

    @SuppressWarnings("unchecked")
    public List<String> getNetworkAllowlist() {
        if (!hasCapability("network")) return List.of();
        Map<String, Object> network = (Map<String, Object>) capabilities.get("network");
        Object allowlist = network.get("allowlist");
        return allowlist instanceof List<?> list
            ? list.stream().map(Object::toString).toList()
            : List.of();
    }

    public String getNetworkQuota() {
        if (!hasCapability("network")) return "100/min";
        Map<String, Object> network = (Map<String, Object>) capabilities.get("network");
        Object quota = network.get("quota");
        return quota != null ? quota.toString() : "100/min";
    }
}
