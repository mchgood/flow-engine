package io.github.mchgood.flow;

public interface ConditionEvaluator {
    CompiledCondition parse(String expression, SourceLocation location);
    boolean evaluate(CompiledCondition expression, NodeContext context);
}
