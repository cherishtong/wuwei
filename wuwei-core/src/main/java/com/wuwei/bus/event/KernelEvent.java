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
    KernelEvent.CapabilityProxyResult
{
    record KernelReady(String version, int port) implements KernelEvent {}

    record SkillActivated(
        String skillId,
        Object ui,
        List<Object> patches,
        String runtime,
        String handlersJs,
        Map<String, Object> capabilities
    ) implements KernelEvent {}

    record A2uiPatch(
        String skillId,
        List<Object> patches
    ) implements KernelEvent {}

    record EventAck(
        String skillId,
        String eventId,
        String status,
        long latencyMs
    ) implements KernelEvent {}

    record GateRequest(
        String skillId,
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
        String desc
    ) implements KernelEvent {}

    record RepairAttempt(
        String skillId,
        int attempt,
        String error
    ) implements KernelEvent {}

    record SkillList(List<SkillMeta> skills) implements KernelEvent {}

    record SkillLoading(String skillId) implements KernelEvent {}

    record SkillDeactivated(String skillId) implements KernelEvent {}

    record SystemNotify(String title, String body) implements KernelEvent {}

    record SkillMeta(String id, String name, String status, String version) {}

    record CapabilityProxyResult(
        String skillId,
        String requestId,
        Object result,
        String error
    ) implements KernelEvent {}
}
