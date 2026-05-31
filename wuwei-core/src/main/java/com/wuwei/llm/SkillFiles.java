package com.wuwei.llm;

import java.util.Map;

/**
 * File bundle produced by LLM generation.
 * In single-file mode: skillJson + uiJson + handlersJs, extra maps are null.
 * In multi-file mode: handlerModules (path→source) and uiFragments (path→json).
 */
public record SkillFiles(
    String skillJson,
    String uiJson,
    String handlersJs,
    Map<String, String> handlerModules,
    Map<String, String> uiFragments
) {
    /** Backward-compatible constructor for single-file skills. */
    public SkillFiles(String skillJson, String uiJson, String handlersJs) {
        this(skillJson, uiJson, handlersJs, null, null);
    }
}
