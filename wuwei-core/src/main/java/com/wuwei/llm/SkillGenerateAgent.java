package com.wuwei.llm;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * AiServices agent for skill generation and refinement.
 * {@code @MemoryId} binds per-skill ChatMemory so the LLM sees full evolution history.
 */
public interface SkillGenerateAgent {

    @SystemMessage(fromResource = "prompts/generate.txt")
    TokenStream generate(@UserMessage String userMessage, @MemoryId String skillId);
}
