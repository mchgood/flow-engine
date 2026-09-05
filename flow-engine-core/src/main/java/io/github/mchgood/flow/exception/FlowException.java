package io.github.mchgood.flow.exception;

/**
 * 带稳定错误码的引擎运行时异常。
 * <p>注册与准入失败可直接抛给宿主；工作线程中的此异常通常转为 FlowError 并保留 code。
 * message 面向诊断，不应通过解析其文本进行业务分支判断。
 */
public class FlowException extends RuntimeException {
    private final String code;

    /**
     * 创建不携带原因链的异常。
     *
     * @param code 错误码
     * @param message 诊断信息
     */
    public FlowException(String code, String message) { super(message); this.code=code; }

    /**
     * 创建保留原因链的异常。
     *
     * @param code 错误码
     * @param message 诊断信息
     * @param cause 原始异常，可为 null
     */
    public FlowException(String code, String message, Throwable cause) { super(message,cause); this.code=code; }

    /**
     * 返回机器可判断的错误码。
     *
     * @return 构造时传入的错误码
     */
    public String code() { return code; }
}
