package io.github.mchgood.flow.result;

/**
 * 流程级状态；逻辑终态不代表所有业务线程物理退出。
 */
public enum FlowStatus {

    /**
     * 正在执行；标准同步入口返回终态结果。
     */
    RUNNING,

    /**
     * 所有已激活路径成功，且到达结束节点。
     */
    SUCCEEDED,

    /**
     * 业务、调度、关闭或调用中断导致失败。
     */
    FAILED,

    /**
     * 流程期限耗尽，执行树被强制终结。
     */
    TIMED_OUT
}
