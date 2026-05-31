package com.wuwei.skill;

import java.util.Map;
import java.util.Set;

/**
 * The genome files that make up a Skill's logic.
 * In single-file mode: uiJson + handlersJs, moduleFiles is null.
 * In multi-file mode: uiJson is the resolved tree, handlersJs is index.js,
 * and moduleFiles maps relative paths to JS source.
 */
public record SkillGenome(
    String uiJson,
    String handlersJs,
    Map<String, String> moduleFiles
) {
    /** Backward-compatible constructor for single-file skills. */
    public SkillGenome(String uiJson, String handlersJs) {
        this(uiJson, handlersJs, null);
    }

    public Set<String> extractEmittedEvents() {
        return Set.of();
    }
}
