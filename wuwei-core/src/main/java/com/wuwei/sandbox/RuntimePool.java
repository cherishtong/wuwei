package com.wuwei.sandbox;

import com.wuwei.capability.CapabilitySet;
import com.wuwei.skill.SkillManifest;
import org.graalvm.polyglot.Engine;

import java.util.List;
import java.util.function.Consumer;

/**
 * Manages GraalJS Engine instances.
 * All Skills share one Engine (shared JIT cache, lower memory),
 * but each Skill gets an isolated Context.
 * Engines are lazily initialized to avoid build-time Truffle issues in native-image.
 */
public class RuntimePool {

    private static volatile Engine sharedEngine;
    private static volatile Engine auditEngine;

    private static Engine getSharedEngine() {
        Engine e = sharedEngine;
        if (e == null) {
            synchronized (RuntimePool.class) {
                e = sharedEngine;
                if (e == null) {
                    e = Engine.newBuilder()
                        .option("engine.WarnInterpreterOnly", "false")
                        .build();
                    sharedEngine = e;
                }
            }
        }
        return e;
    }

    /** Separate Engine for static AST auditing (never mixed with runtime). */
    public static Engine getAuditEngine() {
        Engine e = auditEngine;
        if (e == null) {
            synchronized (RuntimePool.class) {
                e = auditEngine;
                if (e == null) {
                    e = Engine.create();
                    auditEngine = e;
                }
            }
        }
        return e;
    }

    /**
     * Create a SkillRuntime with the shared engine, isolated Context,
     * and capability injection based on the manifest.
     */
    public SkillRuntime create(SkillManifest manifest, String code,
                                CapabilitySet capSet,
                                Consumer<List<Object>> patchFlush) {
        return new SkillRuntime(getSharedEngine(), manifest, code, capSet, patchFlush);
    }

    /**
     * Total number of active Skill contexts (for monitoring).
     */
    public int activeCount() {
        return SkillRuntime.getActiveCount();
    }
}
