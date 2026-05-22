package com.wuwei.llm;

/**
 * Replaces PiMonoException. Carries an error code for compatibility
 * with existing error handling in SkillGenerator and AiCapability.
 */
public class LlmException extends Exception {
    private final int code;

    public LlmException(int code, String message) {
        super(message);
        this.code = code;
    }

    public LlmException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int code() { return code; }
}
