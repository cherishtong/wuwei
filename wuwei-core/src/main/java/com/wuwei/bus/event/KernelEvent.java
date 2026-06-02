package com.wuwei.bus.event;

import java.util.List;
import java.util.Map;

/**
 * Unified event model — all events flowing through the kernel are
 * represented as records implementing this sealed interface.
 */
public sealed interface KernelEvent permits
    KernelEvent.KernelReady,
    KernelEvent.SkillList,
    KernelEvent.SkillLoading,
    KernelEvent.SkillActivated,
    KernelEvent.SkillDeactivated,
    KernelEvent.A2uiPatch,
    KernelEvent.EventAck,
    KernelEvent.GateRequest,
    KernelEvent.PlanStep,
    KernelEvent.RepairAttempt,
    KernelEvent.SkillLog,
    KernelEvent.SystemNotify,
    KernelEvent.GuardianWarning,
    KernelEvent.KernelError,
    KernelEvent.CapabilityProxyResult,
    KernelEvent.SkillHandoff,
    KernelEvent.PiLog
{
    record KernelReady(String version, int port) implements KernelEvent {}

    record SkillActivated(
        String skillId,
        String skillName,
        String threadId,
        Object ui,
        List<Object> patches,
        String runtime,
        String handlersJs,
        Map<String, Object> capabilities
    ) implements KernelEvent {}

    record A2uiPatch(
        String skillId,
        String threadId,
        List<Object> patches
    ) implements KernelEvent {}

    record EventAck(
        String skillId,
        String eventId,
        String status,
        long latencyMs,
        List<Object> patches
    ) implements KernelEvent {}

    record GateRequest(
        String skillId,
        String threadId,
        String capName,
        String reason
    ) implements KernelEvent {}

    record SkillLog(
        String skillId,
        String level,
        String message
    ) implements KernelEvent {}

    record GuardianWarning(
        String type,
        String skillId,
        String message
    ) implements KernelEvent {}

    record KernelError(
        String skillId,
        String code,
        String message
    ) implements KernelEvent {}

    record PlanStep(
        String status,
        String desc,
        String threadId
    ) implements KernelEvent {}

    record RepairAttempt(
        String skillId,
        int attempt,
        String error
    ) implements KernelEvent {}

    record SkillList(List<SkillMeta> skills) implements KernelEvent {}

    record SkillLoading(String skillId) implements KernelEvent {}

    record SkillDeactivated(String skillId, String threadId) implements KernelEvent {}

    record SystemNotify(String title, String body) implements KernelEvent {}

    record SkillMeta(String id, String name, String status, String version,
                     Map<String, Object> capabilities) {}

    record CapabilityProxyResult(
        String skillId,
        String requestId,
        Object result,
        String error
    ) implements KernelEvent {}

    record SkillHandoff(
        String fromSkillId,
        String toSkillId,
        String threadId,
        Map<String, Object> context
    ) implements KernelEvent {}

    record PiLog(
        String level,
        String message,
        String data,
        long timestamp
    ) implements KernelEvent {}
}
