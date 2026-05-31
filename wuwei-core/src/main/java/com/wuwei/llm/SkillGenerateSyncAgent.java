package com.wuwei.llm;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Synchronous (non-streaming) AiServices agent for skill generation.
 * Returns the full LLM output as a String, avoiding streaming tool-call bugs.
 */
public interface SkillGenerateSyncAgent {

    @SystemMessage(fromResource = "prompts/generate.txt")
    String generate(@UserMessage String userMessage, @MemoryId String skillId);
}
