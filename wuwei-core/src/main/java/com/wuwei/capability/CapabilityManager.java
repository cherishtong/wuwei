package com.wuwei.capability;

import com.wuwei.bus.EventBus;
import com.wuwei.bus.event.KernelEvent;
import com.wuwei.skill.SkillManifest;
import com.wuwei.store.SkillStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Factory that builds and injects a CapabilitySet for a Skill
 * based on its declared capabilities in skill.json.
 * Also manages dynamic permission requests and capability revocation.
 */
public class CapabilityManager {

    private static final Logger log = LoggerFactory.getLogger(CapabilityManager.class);

    private final SkillStateStore stateStore;
    private final NetworkCapability networkCap;
    private final FileCapability fileCap;
    private final AiCapability aiCap;
    private final EventBus eventBus;

    /** Pending gate requests: key = "skillId:capName", value = CompletableFuture to complete */
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingGates = new ConcurrentHashMap<>();

    /** Active CapabilitySets keyed by skillId — for runtime injection/NoOp */
    private final ConcurrentHashMap<String, CapabilitySet> activeCapSets = new ConcurrentHashMap<>();

    public CapabilityManager(SkillStateStore stateStore, NetworkCapability networkCap,
                             FileCapability fileCap, AiCapability aiCap, EventBus eventBus) {
        this.stateStore = stateStore;
        this.networkCap = networkCap;
        this.fileCap = fileCap;
        this.aiCap = aiCap;
        this.eventBus = eventBus;
    }

    /**
     * Build the complete capability set for a skill and register it for
     * potential runtime injection.
     */
    public CapabilitySet inject(SkillManifest manifest) {
        CapabilitySet capSet = CapabilitySet.build(manifest, stateStore, networkCap, fileCap, aiCap, eventBus, this);
        activeCapSets.put(manifest.id(), capSet);
        return capSet;
    }

    /** Remove the capability set when a skill is unloaded. */
    public void remove(String skillId) {
        activeCapSets.remove(skillId);
    }

    // ── Dynamic Permission ──────────────────────────────────────────

    /**
     * Called synchronously from a Skill's capability.permission.request().
     * Blocks the GraalJS thread until the user responds via confirm-gate WS message,
     * with a 5-minute timeout.
     */
    public boolean requestPermission(String skillId, String capName, String reason) {
        String gateKey = skillId + ":" + capName;
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingGates.put(gateKey, future);

        eventBus.publish(new KernelEvent.GateRequest(skillId, capName, reason));
        log.info("Gate request published: {} cap={}", skillId, capName);

        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.warn("Gate request timed out: {} cap={}", skillId, capName);
            pendingGates.remove(gateKey);
            return false;
        } catch (Exception e) {
            log.warn("Gate request interrupted: {} cap={}", skillId, capName);
            pendingGates.remove(gateKey);
            return false;
        }
    }

    /**
     * Called from MessageRouter when the user responds to a gate dialog.
     * Approves → inject real capability. Denies → inject NoOp stub.
     */
    public void resolveGate(String skillId, String capName, boolean approved) {
        String gateKey = skillId + ":" + capName;
        CompletableFuture<Boolean> future = pendingGates.remove(gateKey);
        if (future != null) {
            // Inject or NoOp before completing the future so the handler sees the result
            CapabilitySet capSet = activeCapSets.get(skillId);
            if (capSet != null) {
                if (approved) {
                    capSet.injectCapability(capName);
                } else {
                    capSet.injectNoop(capName);
                }
            } else {
                log.warn("No active CapabilitySet for {}, skipping injection", skillId);
            }
            future.complete(approved);
            if (approved) {
                log.info("Gate resolved: {} cap={} GRANTED", skillId, capName);
            } else {
                log.warn("Gate resolved: {} cap={} DENIED — capability NoOp injected", skillId, capName);
                eventBus.publish(new KernelEvent.SystemNotify(
                    "权限已拒绝", skillId + " 的 " + capName + " 能力已被禁用"));
            }
        } else {
            log.warn("No pending gate for {}:{}, response ignored", skillId, capName);
        }
    }

    // ── Revoke ───────────────────────────────────────────────────────

    /**
     * Revoke a capability from a running Skill by replacing it with a NoOp.
     */
    public void revoke(String skillId, String capName) {
        log.info("Capability revoked: {} cap={}", skillId, capName);
        CapabilitySet capSet = activeCapSets.get(skillId);
        if (capSet != null) {
            capSet.injectNoop(capName);
        }
        eventBus.publish(new KernelEvent.KernelError(skillId, "CAPABILITY_REVOKED",
            "能力 " + capName + " 已被撤销"));
    }

    /**
     * Cancel any pending gate requests for a skill (e.g., on uninstall).
     */
    public void cancelPendingGates(String skillId) {
        pendingGates.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(skillId + ":")) {
                entry.getValue().complete(false);
                return true;
            }
            return false;
        });
        activeCapSets.remove(skillId);
    }
}
