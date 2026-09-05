package io.github.mchgood.flow.api;

import java.time.Duration;

/**
 * 单次根流程执行选项；不可变，可在调用间复用。
 * <p>仅覆盖本次流程期限，不改变引擎级节点、网关和资源配置。
 *
 * @param timeout 流程期限；null 使用 EngineConfig.flowTimeout()，非 null 必须大于零且不超过一天
 */
public record ExecutionOptions(Duration timeout) {

    /**
     * 校验本次期限。
     *
     * @throws IllegalArgumentException 非 null 期限不在大于零且不超过一天的范围内
     */
    public ExecutionOptions { if(timeout!=null&&(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofDays(1))>0))throw new IllegalArgumentException("timeout must be in (0, 1 day]"); }

    /**
     * 沿用引擎默认流程期限。
     *
     * @return timeout 为 null 的执行选项
     */
    public static ExecutionOptions defaults(){return new ExecutionOptions(null);}

    /**
     * 指定本次根流程期限。
     *
     * @param timeout 本次期限；null 表示沿用默认
     * @return 新的执行选项
     * @throws IllegalArgumentException 非 null 期限越界
     */
    public static ExecutionOptions withTimeout(Duration timeout){return new ExecutionOptions(timeout);}
}
