package com.wuwei.llm;

/**
 * Generic AI Q&A result used by {@link com.wuwei.capability.AiCapability}.
 */
public record AiResult(int status, String body) {}
