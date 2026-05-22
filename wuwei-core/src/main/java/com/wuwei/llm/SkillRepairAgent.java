package com.wuwei.llm;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * AiServices agent for audit-failure repair.
 */
public interface SkillRepairAgent {

    @SystemMessage(fromResource = "prompts/repair.txt")
    TokenStream repair(@UserMessage String userMessage, @MemoryId String skillId);
}
