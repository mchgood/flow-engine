package io.github.mchgood.flow;

import java.time.Duration;
public record ExecutionOptions(Duration timeout) {
    public ExecutionOptions { if(timeout!=null&&(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofDays(1))>0))throw new IllegalArgumentException("timeout must be in (0, 1 day]"); }
    public static ExecutionOptions defaults(){return new ExecutionOptions(null);}
    public static ExecutionOptions withTimeout(Duration timeout){return new ExecutionOptions(timeout);}
}
