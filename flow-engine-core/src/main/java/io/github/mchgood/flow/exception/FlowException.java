package io.github.mchgood.flow.exception;

public class FlowException extends RuntimeException {
    private final String code;
    public FlowException(String code, String message) { super(message); this.code=code; }
    public FlowException(String code, String message, Throwable cause) { super(message,cause); this.code=code; }
    public String code() { return code; }
}
