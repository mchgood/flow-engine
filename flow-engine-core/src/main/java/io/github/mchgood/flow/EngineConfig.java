package io.github.mchgood.flow;

import java.time.Duration;
public record EngineConfig(int workerThreads,int queueCapacity,int maxConcurrentExecutions,
    int maxInFlightPerExecution,int maxSubflowDepth,int maxExecutionsPerRoot,int maxActiveChildren,
    Duration nodeTimeout,Duration gatewayTimeout,Duration flowTimeout,Duration closeTimeout) {
    public EngineConfig {
        if(workerThreads<1||queueCapacity<1||maxConcurrentExecutions<1||maxInFlightPerExecution<1||maxSubflowDepth<0||maxSubflowDepth>32||maxExecutionsPerRoot<1||maxActiveChildren<1)throw new IllegalArgumentException("Invalid capacities");
        for(var d:new Duration[]{nodeTimeout,gatewayTimeout,flowTimeout,closeTimeout})
            if(d==null||d.isZero()||d.isNegative()||d.compareTo(Duration.ofDays(1))>0)throw new IllegalArgumentException("Invalid timeout");
    }
    public static EngineConfig defaults(){return new EngineConfig(8,128,64,8,8,128,32,Duration.ofSeconds(30),Duration.ofSeconds(1),Duration.ofSeconds(60),Duration.ofSeconds(10));}
}
