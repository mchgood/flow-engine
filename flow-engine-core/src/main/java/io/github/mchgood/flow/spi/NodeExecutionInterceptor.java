package io.github.mchgood.flow.spi;

import io.github.mchgood.flow.node.NodeContext;

/**
 * 业务节点执行拦截器扩展点：仅拦截实际开始运行的 TASK 节点；
 * 网关、汇合、起止节点与子调用不产生本接口回调。
 * <p>调用约定：beforeNode 至多一次；节点到达终态后 afterNode 必调一次，随后按终态互斥
 * 调用 onSuccess 或 onFailure。全部钩子在执行该节点的 worker 线程、引擎根协调锁外执行；
 * beforeNode 的耗时占用该节点的 nodeTimeout 预算，超期按节点超时处理。
 * <p>异常语义：beforeNode 抛出非 VirtualMachineError 异常时，节点以错误码
 * INTERCEPTOR_FAILED 失败并按既有失败语义停止传播；其余钩子的异常被引擎记录后忽略，
 * 不改变节点与流程终态。因准入即过期、强制终止或未激活而从未运行的节点不触发任何钩子。
 *
 * @see FlowExecutionInterceptor
 */
public interface NodeExecutionInterceptor {

    /**
     * 业务 Bean 执行前回调；抛出异常将使节点以 INTERCEPTOR_FAILED 失败。
     *
     * @param context 与业务 Bean 相同的节点上下文
     */
    default void beforeNode(NodeContext context) {
    }

    /**
     * 节点到达终态后回调；无论成败必调一次。
     *
     * @param context 节点上下文
     * @param outcome 节点终态快照
     */
    default void afterNode(NodeContext context, NodeOutcome outcome) {
    }

    /**
     * 节点成功终态回调；在 afterNode 之后调用，与 onFailure 互斥。
     *
     * @param context 节点上下文
     * @param value 业务输出，可能为 null
     */
    default void onSuccess(NodeContext context, Object value) {
    }

    /**
     * 节点失败或超时终态回调；在 afterNode 之后调用，与 onSuccess 互斥。
     *
     * @param context 节点上下文
     * @param errorCode 终态错误码，与结果诊断一致
     * @param message 终态错误信息
     */
    default void onFailure(NodeContext context, String errorCode, String message) {
    }
}
