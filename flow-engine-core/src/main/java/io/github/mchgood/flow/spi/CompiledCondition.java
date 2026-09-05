package io.github.mchgood.flow.spi;

/**
 * 编译后条件的标记接口；由 ConditionEvaluator 创建并解释。
 * <p>引擎不检查内部字段。实现应支持跨执行并发复用，不能存放某次求值的可变上下文；
 * 只应交回创建它的求值器实现，不同实现之间不保证兼容。
 */
public interface CompiledCondition {}
