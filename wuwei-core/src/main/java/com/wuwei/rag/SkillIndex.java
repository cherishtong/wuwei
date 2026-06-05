package com.wuwei.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** Data model for the PageIndex-style skill index tree. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillIndex(
    @JsonProperty("version") int version,
    @JsonProperty("updatedAt") String updatedAt,
    @JsonProperty("skills") Map<String, SkillEntry> skills
) {
    public record SkillEntry(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("capabilities") List<String> capabilities,
        @JsonProperty("patterns") List<String> patterns,
        @JsonProperty("functions") List<FunctionSummary> functions,
        @JsonProperty("components") List<ComponentSummary> components,
        @JsonProperty("complexity") String complexity
    ) {}

    public record FunctionSummary(
        @JsonProperty("name") String name,
        @JsonProperty("summary") String summary
    ) {}

    public record ComponentSummary(
        @JsonProperty("id") String id,
        @JsonProperty("summary") String summary
    ) {}
}
