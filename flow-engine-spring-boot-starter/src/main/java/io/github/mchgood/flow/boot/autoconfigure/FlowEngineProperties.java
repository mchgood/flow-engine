package io.github.mchgood.flow.boot.autoconfigure;

import io.github.mchgood.flow.config.EngineConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** Resource limits for the auto-configured engine. Durations accept units such as 500ms or 30s. */
@ConfigurationProperties(prefix = "flow-engine", ignoreUnknownFields = false)
public class FlowEngineProperties {
    private static final EngineConfig DEFAULTS = EngineConfig.defaults();
    private boolean enabled = true;
    private int workerThreads = DEFAULTS.workerThreads();
    private int queueCapacity = DEFAULTS.queueCapacity();
    private int maxConcurrentExecutions = DEFAULTS.maxConcurrentExecutions();
    private int maxInFlightPerExecution = DEFAULTS.maxInFlightPerExecution();
    private int maxSubflowDepth = DEFAULTS.maxSubflowDepth();
    private int maxExecutionsPerRoot = DEFAULTS.maxExecutionsPerRoot();
    private int maxActiveChildren = DEFAULTS.maxActiveChildren();
    private Duration nodeTimeout = DEFAULTS.nodeTimeout();
    private Duration gatewayTimeout = DEFAULTS.gatewayTimeout();
    private Duration flowTimeout = DEFAULTS.flowTimeout();
    private Duration closeTimeout = DEFAULTS.closeTimeout();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public int getMaxConcurrentExecutions() { return maxConcurrentExecutions; }
    public void setMaxConcurrentExecutions(int maxConcurrentExecutions) { this.maxConcurrentExecutions = maxConcurrentExecutions; }

    public int getMaxInFlightPerExecution() { return maxInFlightPerExecution; }
    public void setMaxInFlightPerExecution(int maxInFlightPerExecution) { this.maxInFlightPerExecution = maxInFlightPerExecution; }

    public int getMaxSubflowDepth() { return maxSubflowDepth; }
    public void setMaxSubflowDepth(int maxSubflowDepth) { this.maxSubflowDepth = maxSubflowDepth; }

    public int getMaxExecutionsPerRoot() { return maxExecutionsPerRoot; }
    public void setMaxExecutionsPerRoot(int maxExecutionsPerRoot) { this.maxExecutionsPerRoot = maxExecutionsPerRoot; }

    public int getMaxActiveChildren() { return maxActiveChildren; }
    public void setMaxActiveChildren(int maxActiveChildren) { this.maxActiveChildren = maxActiveChildren; }

    public Duration getNodeTimeout() { return nodeTimeout; }
    public void setNodeTimeout(Duration nodeTimeout) { this.nodeTimeout = nodeTimeout; }

    public Duration getGatewayTimeout() { return gatewayTimeout; }
    public void setGatewayTimeout(Duration gatewayTimeout) { this.gatewayTimeout = gatewayTimeout; }

    public Duration getFlowTimeout() { return flowTimeout; }
    public void setFlowTimeout(Duration flowTimeout) { this.flowTimeout = flowTimeout; }

    public Duration getCloseTimeout() { return closeTimeout; }
    public void setCloseTimeout(Duration closeTimeout) { this.closeTimeout = closeTimeout; }

    public EngineConfig toEngineConfig() {
        return new EngineConfig(workerThreads, queueCapacity, maxConcurrentExecutions, maxInFlightPerExecution, maxSubflowDepth, maxExecutionsPerRoot, maxActiveChildren, nodeTimeout, gatewayTimeout, flowTimeout, closeTimeout);
    }
}
