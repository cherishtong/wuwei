package com.wuwei.llm;

import java.util.List;
import java.util.Map;

/**
 * Builds user messages for LLM agents.
 * Replaces PromptTemplates string concatenation — the system prompts are now
 * handled by {@code @SystemMessage(fromResource = ...)} on each agent interface.
 */
public final class PromptBuilder {

    private PromptBuilder() {}

    /**
     * Build user message for skill generation / refinement.
     */
    public static String buildGenerate(
            String intent,
            List<String> existingSkills,
            Map<String, Object> memoryCtx,
            Map<String, String> currentFiles) {

        StringBuilder sb = new StringBuilder();

        // Memory section (intent.lock + design.md + ChatMemory summary)
        if (memoryCtx != null && !memoryCtx.isEmpty()) {
            sb.append("## Skill 记忆\n\n");
            if (memoryCtx.containsKey("originalIntent")) {
                sb.append("### 原始用户需求\n").append(memoryCtx.get("originalIntent")).append("\n\n");
            }
            if (memoryCtx.containsKey("coreGoals")) {
                @SuppressWarnings("unchecked")
                var goals = (List<String>) memoryCtx.get("coreGoals");
                if (goals != null && !goals.isEmpty()) {
                    sb.append("### 核心目标（不得丢失）\n");
                    for (String g : goals) {
                        sb.append("- ").append(g).append("\n");
                    }
                    sb.append("\n");
                }
            }
            if (memoryCtx.containsKey("recentDesigns")) {
                sb.append("### 设计决策历史\n").append(memoryCtx.get("recentDesigns")).append("\n\n");
            }
        }

        // Existing skills section
        if (existingSkills != null && !existingSkills.isEmpty()
                && !String.join("", existingSkills).isBlank()
                && !String.join("", existingSkills).contains("暂无")) {
            sb.append("## 当前已存在的 Skill 列表\n\n");
            for (String s : existingSkills) {
                if (s != null && !s.isBlank()) {
                    sb.append(s).append("\n");
                }
            }
            sb.append("\n参考现有 Skill 的设计模式，但必须创建用户需要的新 Skill。\n\n");
        }

        // Current files section (for refine)
        if (currentFiles != null && !currentFiles.isEmpty()) {
            sb.append("## 当前 Skill 文件\n\n以下是当前 Skill 的三个文件，请在这些文件的基础上进行优化：\n\n");
            var skillJson = currentFiles.get("skillJson");
            if (skillJson != null) sb.append("=== skill.json ===\n").append(skillJson).append("\n\n");
            var uiJson = currentFiles.get("uiJson");
            if (uiJson != null) sb.append("=== ui.json ===\n").append(uiJson).append("\n\n");
            var handlersJs = currentFiles.get("handlersJs");
            if (handlersJs != null) sb.append("=== handlers.js ===\n").append(handlersJs).append("\n\n");
        }

        // User intent
        sb.append("## 用户需求\n\n").append(intent);

        return sb.toString();
    }

    /**
     * Build user message for repair (audit failure).
     */
    public static String buildRepair(String error, SkillFiles files, String originalIntent, int attempt) {
        StringBuilder sb = new StringBuilder();
        sb.append("这是第 ").append(attempt).append(" 次修复尝试。\n\n");

        if (originalIntent != null && !originalIntent.isBlank()) {
            sb.append("## 原始用户需求\n\n").append(originalIntent).append("\n\n");
        }

        sb.append("## 修复前的当前文件内容\n\n");
        sb.append("=== skill.json ===\n").append(files.skillJson()).append("\n\n");
        sb.append("=== ui.json ===\n").append(files.uiJson()).append("\n\n");
        sb.append("=== handlers.js ===\n").append(files.handlersJs()).append("\n\n");

        sb.append("## 审计错误\n\n").append(error);
        sb.append("\n修复要求：\n");
        sb.append("1. 修复上述审计错误\n");
        sb.append("2. 保留所有原有功能\n");
        sb.append("3. 不得添加未在原始需求中提及的新功能\n");

        return sb.toString();
    }

    /**
     * Build user message for drift analysis.
     */
    public static String buildDriftAnalysis(
            String originalIntent,
            List<String> coreGoals,
            String currentHandlersJs,
            String proposedChange) {

        StringBuilder sb = new StringBuilder();
        sb.append("原始意图: ").append(originalIntent).append("\n\n");

        if (coreGoals != null && !coreGoals.isEmpty()) {
            sb.append("核心目标:\n");
            for (String g : coreGoals) sb.append("- ").append(g).append("\n");
            sb.append("\n");
        }

        if (currentHandlersJs != null) {
            String truncated = currentHandlersJs.length() > 2000
                ? currentHandlersJs.substring(0, 2000) + "..."
                : currentHandlersJs;
            sb.append("当前 handlers.js:\n").append(truncated).append("\n\n");
        }

        sb.append("提议的变更: ").append(proposedChange).append("\n\n");
        sb.append("这次变更是否偏离了原始意图？");

        return sb.toString();
    }

    /**
     * Build user message for memory summarization.
     */
    public static String buildMemorySummary(String evolutionLog, int maxLength) {
        return "将以下 Skill 进化日志压缩为不超过 " + maxLength
            + " 字符的摘要。保留关键决策和变更历史。\n\n进化日志：\n" + evolutionLog;
    }
}
