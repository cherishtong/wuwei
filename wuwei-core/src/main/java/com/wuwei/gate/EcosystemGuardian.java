package com.wuwei.gate;

import com.wuwei.bus.EventBus;
import org.springframework.stereotype.Component;
import com.wuwei.bus.event.KernelEvent;
import com.wuwei.skill.SkillGenome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-skill ecosystem guardian.
 * Detects event name collisions and other cross-skill conflicts.
 */
@Component
public class EcosystemGuardian {

    private static final Logger log = LoggerFactory.getLogger(EcosystemGuardian.class);

    private final EventBus eventBus;
    private final Map<String, Set<String>> skillEmittedEvents = new ConcurrentHashMap<>();

    // Matches capability.events.emit("eventName", ...)
    private static final Pattern EMIT_PATTERN =
        Pattern.compile("capability\\.events\\.emit\\s*\\(\\s*[\"']([^\"']+)[\"']");

    public EcosystemGuardian(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * Check a new/updated skill for cross-skill conflicts.
     * Called during skill loading (before activation).
     */
    public void check(String skillId, SkillGenome genome) {
        Set<String> emittedEvents = extractEmittedEvents(genome.handlersJs());
        if (emittedEvents.isEmpty()) {
            skillEmittedEvents.remove(skillId);
            return;
        }

        // Check collisions with other loaded skills
        for (var entry : skillEmittedEvents.entrySet()) {
            if (entry.getKey().equals(skillId)) continue;

            Set<String> collision = new HashSet<>(emittedEvents);
            collision.retainAll(entry.getValue());

            if (!collision.isEmpty()) {
                eventBus.publish(new KernelEvent.GuardianWarning(
                    "EVENT_COLLISION", skillId,
                    "与 " + entry.getKey() + " 发射相同的事件名: " + collision +
                    "（Phase 1 不影响功能，Phase 2 开放跨 Skill 通信时需要解决）"
                ));
                log.warn("Event collision: {} and {} both emit {}", skillId, entry.getKey(), collision);
            }
        }

        skillEmittedEvents.put(skillId, emittedEvents);
    }

    /**
     * Remove a skill from collision tracking when uninstalled.
     */
    public void forget(String skillId) {
        skillEmittedEvents.remove(skillId);
    }

    static Set<String> extractEmittedEvents(String handlersJs) {
        Set<String> events = new HashSet<>();
        Matcher m = EMIT_PATTERN.matcher(handlersJs);
        while (m.find()) {
            events.add(m.group(1));
        }
        return events;
    }
}
