package io.github.mchgood.flow;

@FunctionalInterface
public interface FlowNode { Object execute(NodeContext context) throws Exception; }
