package com.wuwei.llm;

import java.util.List;

/**
 * Structured result from {@link DriftAnalysisAgent}.
 * langchain4j auto-deserializes the LLM's JSON output into this record.
 */
public record DriftResult(
    double driftScore,
    List<String> retainedGoals,
    List<String> lostGoals,
    List<String> newGoals,
    String reason,
    String recommendation
) {}
