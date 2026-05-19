package com.wuwei.llm;

/**
 * Exception thrown when Pi process returns a JSON-RPC error.
 */
public class PiMonoException extends Exception {

    private final int code;

    public PiMonoException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
