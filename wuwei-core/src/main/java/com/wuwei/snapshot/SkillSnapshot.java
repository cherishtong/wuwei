package com.wuwei.snapshot;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A point-in-time snapshot of a Skill's full state.
 */
public record SkillSnapshot(
    String skillId,
    String version,
    String abiVersion,
    long snapshotTime,
    String reason,
    JsonNode uiTree,
    String stateSummary   // comma-separated key list from state.db
) {}
