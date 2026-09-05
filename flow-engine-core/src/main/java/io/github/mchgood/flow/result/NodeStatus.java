package io.github.mchgood.flow.result;

/**
 * 单个图节点调用的状态；不同别名拥有独立状态。
 */
public enum NodeStatus {

    /**
     * 尚未实际开始，也可能已经排队。
     */
    PENDING,

    /**
     * 已开始业务、网关求值或子流程调用。
     */
    RUNNING,

    /**
     * 成功完成，输出可以为 null。
     */
    SUCCEEDED,

    /**
     * 执行失败。
     */
    FAILED,

    /**
     * 逻辑期限耗尽，底层代码可能仍在退出中。
     */
    TIMED_OUT,

    /**
     * 未选分支或流程停止导致不执行。
     */
    SKIPPED
}
