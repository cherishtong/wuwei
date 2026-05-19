package com.wuwei.llm;

/**
 * Progress notification from Pi process (skill/progress).
 */
public record ProgressNotification(
    String requestId,
    String message,
    int percent
) {}
