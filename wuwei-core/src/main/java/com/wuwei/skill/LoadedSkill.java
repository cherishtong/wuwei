package com.wuwei.skill;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A loaded and running Skill within the kernel.
 */
public record LoadedSkill(
    SkillManifest manifest,
    Object runtime,          // SkillRuntime (avoid circular dependency)
    JsonNode uiTree,
    SkillStatus status
) {
    public static LoadedSkill create(SkillManifest manifest, Object runtime, JsonNode uiTree) {
        return new LoadedSkill(manifest, runtime, uiTree, SkillStatus.RUNNING);
    }

    public LoadedSkill withStatus(SkillStatus newStatus) {
        return new LoadedSkill(manifest, runtime, uiTree, newStatus);
    }
}
