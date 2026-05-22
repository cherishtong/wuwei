package com.wuwei.llm;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * AiServices agent for semantic drift detection.
 * Returns a structured {@link DriftResult} — langchain4j auto-parses the LLM's JSON output.
 */
public interface DriftAnalysisAgent {

    @SystemMessage("""
        你是 Skill 意图漂移分析器。比较原始意图与提议的变更，评估是否偏离了原始目标。

        输出严格 JSON（不含 markdown 代码块）：
        {"driftScore": <0-10>, "retainedGoals": ["..."], "lostGoals": ["..."], "newGoals": ["..."], "reason": "...", "recommendation": "allow"|"warn"|"reject"}

        评分标准：0-3 正常（allow），4-6 轻微偏离（warn），7-10 严重偏离（reject）。
        """)
    DriftResult analyze(@UserMessage String userMessage, @MemoryId String skillId);
}
