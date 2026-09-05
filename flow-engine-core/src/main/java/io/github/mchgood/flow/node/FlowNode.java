package io.github.mchgood.flow.node;

@FunctionalInterface
public interface FlowNode { Object execute(NodeContext context) throws Exception; }
