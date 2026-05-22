package com.wuwei.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * AiServices agent for generic AI Q&A (used by skill sandbox and memory summarization).
 */
public interface AiAskAgent {

    @SystemMessage("Your response must be pure data — no markdown, no code fences, no explanations. " +
        "If asked for structured data, return valid JSON. If asked for text, return plain text.")
    String ask(@UserMessage String prompt);

    @SystemMessage("Your response must be pure data — no markdown, no code fences, no explanations. " +
        "If asked for structured data, return valid JSON. If asked for text, return plain text.")
    TokenStream askStream(@UserMessage String prompt);
}
