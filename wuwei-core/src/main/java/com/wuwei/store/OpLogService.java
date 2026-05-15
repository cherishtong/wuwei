package com.wuwei.store;

import com.wuwei.bus.event.KernelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Operation log service. Records all kernel events for audit trail.
 */
public class OpLogService {

    private static final Logger log = LoggerFactory.getLogger(OpLogService.class);

    private final StoreService store;

    public OpLogService(StoreService store) {
        this.store = store;
    }

    public void record(KernelEvent event) {
        String opType = event.getClass().getSimpleName();
        // Extract skillId and eventId from event components
        String skillId = extractSkillId(event);
        String eventId = extractEventId(event);
        store.recordOpLog(skillId, opType, eventId, null);
        log.debug("op_log: {} skill={}", opType, skillId);
    }

    private String extractSkillId(KernelEvent event) {
        try {
            for (var rc : event.getClass().getRecordComponents()) {
                if ("skillId".equals(rc.getName())) {
                    Object v = rc.getAccessor().invoke(event);
                    return v != null ? v.toString() : "system";
                }
            }
        } catch (Exception ignored) {}
        return "system";
    }

    private String extractEventId(KernelEvent event) {
        try {
            for (var rc : event.getClass().getRecordComponents()) {
                if ("eventId".equals(rc.getName())) {
                    Object v = rc.getAccessor().invoke(event);
                    return v != null ? v.toString() : null;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
