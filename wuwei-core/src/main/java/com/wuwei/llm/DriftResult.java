package com.wuwei.llm;

import java.util.List;

/**
 * Structured result from skill drift analysis.
 * The LLM's JSON output is deserialized into this record.
 */
public record DriftResult(
    double driftScore,
    List<String> retainedGoals,
    List<String> lostGoals,
    List<String> newGoals,
    String reason,
    String recommendation
) {}
