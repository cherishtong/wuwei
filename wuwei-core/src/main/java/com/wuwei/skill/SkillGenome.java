package com.wuwei.skill;

import java.util.Set;

/**
 * The two genome files that make up a Skill's logic:
 * ui.json (A2UI declaration) and handlers.js (business logic).
 */
public record SkillGenome(
    String uiJson,
    String handlersJs
) {
    public Set<String> extractEmittedEvents() {
        // Simple heuristic: find capability.events.emit("event-name", ...)
        // Phase 2 will use proper AST extraction
        return Set.of();
    }
}
