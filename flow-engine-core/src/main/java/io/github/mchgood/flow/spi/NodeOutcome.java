package io.github.mchgood.flow.spi;

/**
 * 业务节点终态快照，供节点拦截器的后置钩子读取。
 * <p>不可变值对象。成功时 errorCode 与 message 为 null，value 为业务输出（允许为 null）；
 * 失败时 value 为 null，errorCode 与 message 取自该节点的错误记录。由引擎在持锁状态下
 * 构造，拦截器线程只读。
 *
 * @param value 成功终态的业务输出，可为 null
 * @param errorCode 失败终态的错误码；成功为 null
 * @param message 失败终态的错误信息；成功为 null
 */
public record NodeOutcome(Object value, String errorCode, String message) {

    /**
     * 判断节点终态是否成功。
     *
     * @return errorCode 为 null 视为成功
     */
    public boolean succeeded() {
        return errorCode == null;
    }
}
