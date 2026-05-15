package com.wuwei.gate;

/**
 * Thrown when a Skill fails gate validation (AST audit, ID contract, or capability whitelist).
 */
public class GateException extends RuntimeException {
    private final String code;

    public GateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
