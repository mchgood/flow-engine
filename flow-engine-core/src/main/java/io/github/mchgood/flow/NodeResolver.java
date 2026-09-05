package io.github.mchgood.flow;

@FunctionalInterface
public interface NodeResolver { FlowNode resolve(String beanId); }
