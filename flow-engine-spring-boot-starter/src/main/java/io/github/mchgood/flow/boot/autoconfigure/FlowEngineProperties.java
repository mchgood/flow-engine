package io.github.mchgood.flow.boot.autoconfigure;

import io.github.mchgood.flow.config.EngineConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * 以 flow-engine 为前缀的 Spring Boot 启动配置。
 * <p>默认资源值来自 EngineConfig.defaults()；setter 仅负责绑定，资源范围在转为
 * EngineConfig 时校验。未知属性拒绝绑定；本对象不是运行期动态调参入口。
 * 自定义 EngineConfig Bean 会使默认转换工厂退让，属性本身不会覆写该 Bean。
 */
@ConfigurationProperties(prefix = "flow-engine", ignoreUnknownFields = false)
public class FlowEngineProperties {
    private static final EngineConfig DEFAULTS = EngineConfig.defaults();

    /**
     * 是否启用自动装配，默认 true。
     */
    private boolean enabled = true;

    /**
     * 工作线程数，默认 8，至少 1。
     */
    private int workerThreads = DEFAULTS.workerThreads();

    /**
     * 工作队列容量，默认 128，至少 1。
     */
    private int queueCapacity = DEFAULTS.queueCapacity();

    /**
     * 并发根调用上限，默认 64，至少 1。
     */
    private int maxConcurrentExecutions = DEFAULTS.maxConcurrentExecutions();

    /**
     * 每个执行树的在途额度，默认 8，至少 1。
     */
    private int maxInFlightPerExecution = DEFAULTS.maxInFlightPerExecution();

    /**
     * 子流程深度上限，默认 8，范围 0 至 32。
     */
    private int maxSubflowDepth = DEFAULTS.maxSubflowDepth();

    /**
     * 根调用累计实例上限，默认 128，包含根实例。
     */
    private int maxExecutionsPerRoot = DEFAULTS.maxExecutionsPerRoot();

    /**
     * 活跃子实例上限，默认 32，至少 1。
     */
    private int maxActiveChildren = DEFAULTS.maxActiveChildren();

    /**
     * 任务实际开始后的期限，默认 30 秒。
     */
    private Duration nodeTimeout = DEFAULTS.nodeTimeout();

    /**
     * 条件网关求值期限，默认 1 秒。
     */
    private Duration gatewayTimeout = DEFAULTS.gatewayTimeout();

    /**
     * 流程默认期限，默认 60 秒。
     */
    private Duration flowTimeout = DEFAULTS.flowTimeout();

    /**
     * 关闭等待根调用的期限，默认 10 秒。
     */
    private Duration closeTimeout = DEFAULTS.closeTimeout();

    /**
     * 读取是否启用自动装配，默认 true。
     *
     * @return 是否启用自动装配，默认 true
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 绑定是否启用自动装配，默认 true。
     *
     * @param enabled 启动配置值；资源范围在 toEngineConfig() 中校验
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 读取工作线程数，默认 8，至少 1。
     *
     * @return 工作线程数，默认 8，至少 1
     */
    public int getWorkerThreads() { return workerThreads; }

    /**
     * 绑定工作线程数，默认 8，至少 1。
     *
     * @param workerThreads 启动配置值；资源范围在 toEngineConfig() 中校验
     */
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }

    /**
     * 读取工作队列容量，默认 128，至少 1。
     *
     * @return 工作队列容量，默认 128，至少 1
     */
    public int getQueueCapacity() { return queueCapacity; }

    /**
     * 绑定工作队列容量，默认 128，至少 1。
     *
     * @param queueCapacity 启动配置值；资源范围在 toEngineConfig() 中校验
     */
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    /**
     * 读取并发根调用上限，默认 64，至少 1。
     *
     * @return 并发根调用上限，默认 64，至少 1
     */
    public int getMaxConcurrentExecutions() { return maxConcurrentExecutions; }

    /**
     * 绑定并发根调用上限，默认 64，至少 1。
     *
     * @param maxConcurrentExecutions 启动配置值；资源范围在 toEngineConfig() 中校验
     */
    public void setMaxConcurrentExecutions(int maxConcurrentExecutions) { this.maxConcurrentExecutions = maxConcurrentExecutions; }

    /**
     * 读取每个执行树的在途额度，默认 8，至少 1。
     *
     * @return 每个执行树的在途额度，默认 8，至少 1
     */
    public int getMaxInFlightPerExecution() { return maxInFlightPerExecution; }

    /**
     * 绑定每个执行树的在途额度，默认 8，至少 1。
     *
     * @param maxInFlightPerExecution 启动配置值；资源范围在 toEngineConfig() 中校验
     */
    public void setMaxInFlightPerExecution(int maxInFlightPerExecution) { this.maxInFlightPerExecution = maxInFlightPerExecution; }

    /**
     * 读取子流程深度上限，默认 8，范围 0 至 32。
     *
     * @return 子流程深度上限，默认 8，范围 0 至 32
     */
    public int getMaxSubflowDepth() { return maxSubflowDepth; }

    /**
     * 绑定子流程深度上限，默认 8，范围 0 至 32。
     *
     * @param maxSubflowDepth 启动配置值；资源范围在 toEngineConfig() 中校验
     */
    public void setMaxSubflowDepth(int maxSubflowDepth) { this.maxSubflowDepth = maxSubflowDepth; }

    /**
     * 读取根调用累计实例上限，默认 128，包含根实例。
     *
     * @return 根调用累计实例上限，默认 128，包含根实例
     */
    public int getMaxExecutionsPerRoot() { return maxExecutionsPerRoot; }

    /**
     * 绑定根调用累计实例上限，默认 128，包含根实例。
     *
     * @param maxExecutionsPerRoot 启动配置值；资源范围在 toEngineConfig() 中校验
     */
    public void setMaxExecutionsPerRoot(int maxExecutionsPerRoot) { this.maxExecutionsPerRoot = maxExecutionsPerRoot; }

    /**
     * 读取活跃子实例上限，默认 32，至少 1。
     *
     * @return 活跃子实例上限，默认 32，至少 1
     */
    public int getMaxActiveChildren() { return maxActiveChildren; }

    /**
     * 绑定活跃子实例上限，默认 32，至少 1。
     *
     * @param maxActiveChildren 启动配置值；资源范围在 toEngineConfig() 中校验
     */
    public void setMaxActiveChildren(int maxActiveChildren) { this.maxActiveChildren = maxActiveChildren; }

    /**
     * 读取任务实际开始后的期限，默认 30 秒。
     *
     * @return 任务实际开始后的期限，默认 30 秒
     */
    public Duration getNodeTimeout() { return nodeTimeout; }

    /**
     * 绑定任务实际开始后的期限，默认 30 秒。
     *
     * @param nodeTimeout 启动配置值；资源范围在 toEngineConfig() 中校验
     */
    public void setNodeTimeout(Duration nodeTimeout) { this.nodeTimeout = nodeTimeout; }

    /**
     * 读取条件网关求值期限，默认 1 秒。
     *
     * @return 条件网关求值期限，默认 1 秒
     */
    public Duration getGatewayTimeout() { return gatewayTimeout; }

    /**
     * 绑定条件网关求值期限，默认 1 秒。
     *
     * @param gatewayTimeout 启动配置值；资源范围在 toEngineConfig() 中校验
     */
    public void setGatewayTimeout(Duration gatewayTimeout) { this.gatewayTimeout = gatewayTimeout; }

    /**
     * 读取流程默认期限，默认 60 秒。
     *
     * @return 流程默认期限，默认 60 秒
     */
    public Duration getFlowTimeout() { return flowTimeout; }

    /**
     * 绑定流程默认期限，默认 60 秒。
     *
     * @param flowTimeout 启动配置值；资源范围在 toEngineConfig() 中校验
     */
    public void setFlowTimeout(Duration flowTimeout) { this.flowTimeout = flowTimeout; }

    /**
     * 读取关闭等待根调用的期限，默认 10 秒。
     *
     * @return 关闭等待根调用的期限，默认 10 秒
     */
    public Duration getCloseTimeout() { return closeTimeout; }

    /**
     * 绑定关闭等待根调用的期限，默认 10 秒。
     *
     * @param closeTimeout 启动配置值；资源范围在 toEngineConfig() 中校验
     */
    public void setCloseTimeout(Duration closeTimeout) { this.closeTimeout = closeTimeout; }

    /**
     * 创建并校验不可变的资源配置，不保留此可变属性对象引用。
     *
     * @return 新的引擎配置
     * @throws IllegalArgumentException 容量、深度或期限无效
     */
    public EngineConfig toEngineConfig() {
        return new EngineConfig(workerThreads, queueCapacity, maxConcurrentExecutions, maxInFlightPerExecution, maxSubflowDepth, maxExecutionsPerRoot, maxActiveChildren, nodeTimeout, gatewayTimeout, flowTimeout, closeTimeout);
    }
}
