package io.github.mchgood.flow.spi;

import io.github.mchgood.flow.node.NodeContext;

public interface ConditionEvaluator {
    CompiledCondition parse(String expression, SourceLocation location);
    boolean evaluate(CompiledCondition expression, NodeContext context);
}
