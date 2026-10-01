package io.github.mchgood.flow.spi;

import io.github.mchgood.flow.result.FlowResult;

/**
 * 根流程执行拦截器扩展点：在根流程开始前与到达终态后获得通知。
 * <p>仅根流程触发；子流程执行不产生本接口回调。四个钩子均为 default，按需覆盖。
 * 调用约定：beforeFlow 至多一次；到达终态后 afterFlow 必调一次，随后按终态互斥调用
 * onSuccess 或 onFailure。全部钩子在 execute() 调用者线程、引擎根协调锁外执行；
 * 实现必须线程安全且快速返回，阻塞会直接拉长调用者的等待时间。
 * <p>异常语义：beforeFlow 抛出非 VirtualMachineError 异常时，本次执行以错误码
 * INTERCEPTOR_FAILED 快速失败，不运行任何节点；其余钩子的异常被引擎记录后忽略，
 * 不改变已发布的终态。
 *
 * @see NodeExecutionInterceptor
 */
public interface FlowExecutionInterceptor {

    /**
     * 根流程开始前回调；抛出异常将阻止本次执行。
     *
     * @param flowId 被执行的流程 ID
     * @param executionId 本次执行的根实例 ID，与终态 FlowResult.executionId() 一致
     * @param input 调用者提供的原始输入引用
     */
    default void beforeFlow(String flowId, String executionId, Object input) {
    }

    /**
     * 根流程到达终态后回调；无论成败必调一次。
     *
     * @param result 已发布的终态结果快照
     */
    default void afterFlow(FlowResult result) {
    }

    /**
     * 根流程成功终态回调；在 afterFlow 之后调用，与 onFailure 互斥。
     *
     * @param result 成功终态结果快照
     */
    default void onSuccess(FlowResult result) {
    }

    /**
     * 根流程失败或超时终态回调；在 afterFlow 之后调用，与 onSuccess 互斥。
     *
     * @param result 失败终态结果快照
     */
    default void onFailure(FlowResult result) {
    }
}
