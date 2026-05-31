package com.wuwei.llm;

import java.util.List;

/** Structured skill generation plan produced by the Planner agent. */
public record Plan(
    String skillId,
    String runtime,
    List<String> capabilities,
    List<FileSpec> files
) {
    public record FileSpec(String path, String purpose) {}
}
