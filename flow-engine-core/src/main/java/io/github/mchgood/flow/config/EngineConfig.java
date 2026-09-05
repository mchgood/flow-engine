package io.github.mchgood.flow.config;

import java.time.Duration;

/**
 * 引擎创建时固定的资源和超时配置；不可变，不支持运行中修改。
 * <p>在途任务同时计入当前执行及其祖先执行的额度，子流程不能绕过父流程限制。
 * 所有期限均非 null、大于零且不超过一天；无效参数在构造时抛出 IllegalArgumentException。
 *
 * @param workerThreads 共享工作线程数，至少 1
 * @param queueCapacity 共享工作队列容量，至少 1
 * @param maxConcurrentExecutions 同时接纳的根调用数，至少 1；满时立即拒绝
 * @param maxInFlightPerExecution 单执行树中排队或物理未退出任务的额度，至少 1
 * @param maxSubflowDepth 相对根流程的最大嵌套深度，0 至 32，0 禁止子流程
 * @param maxExecutionsPerRoot 一个根流程累计创建的执行实例总数（含根），至少 1
 * @param maxActiveChildren 一个根流程下尚未返回结果的子执行数量上限，至少 1
 * @param nodeTimeout 任务实际开始后计时的节点期限，仍受所属流程期限限制
 * @param gatewayTimeout 条件网关开始后计时的求值期限
 * @param flowTimeout 根流程默认期限及每次子流程的期限上限
 * @param closeTimeout 关闭时等待根调用自然完成的期限；不保证业务线程已经退出
 */
public record EngineConfig(int workerThreads,int queueCapacity,int maxConcurrentExecutions,
    int maxInFlightPerExecution,int maxSubflowDepth,int maxExecutionsPerRoot,int maxActiveChildren,
    Duration nodeTimeout,Duration gatewayTimeout,Duration flowTimeout,Duration closeTimeout) {

    /**
     * 校验容量、子流程深度和期限。
     *
     * @throws IllegalArgumentException 容量、深度或期限越界，或期限为 null
     */
    public EngineConfig {
        if(workerThreads<1||queueCapacity<1||maxConcurrentExecutions<1||maxInFlightPerExecution<1||maxSubflowDepth<0||maxSubflowDepth>32||maxExecutionsPerRoot<1||maxActiveChildren<1)throw new IllegalArgumentException("Invalid capacities");
        for(var d:new Duration[]{nodeTimeout,gatewayTimeout,flowTimeout,closeTimeout})
            if(d==null||d.isZero()||d.isNegative()||d.compareTo(Duration.ofDays(1))>0)throw new IllegalArgumentException("Invalid timeout");
    }

    /**
     * 返回默认资源配置：8 工作线程、128 队列容量、64 根调用容量。
     * <p>节点/网关/流程/关闭期限分别为 30 秒、1 秒、60 秒、10 秒。
     *
     * @return 新的不可变默认配置
     */
    public static EngineConfig defaults(){return new EngineConfig(8,128,64,8,8,128,32,Duration.ofSeconds(30),Duration.ofSeconds(1),Duration.ofSeconds(60),Duration.ofSeconds(10));}
}
