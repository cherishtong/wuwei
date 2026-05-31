package com.wuwei.llm;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * AiServices agent for audit-failure repair (synchronous — avoids DeepSeek streaming bugs).
 */
public interface SkillRepairAgent {

    @SystemMessage(fromResource = "prompts/repair.txt")
    String repair(@UserMessage String userMessage, @MemoryId String skillId);
}
