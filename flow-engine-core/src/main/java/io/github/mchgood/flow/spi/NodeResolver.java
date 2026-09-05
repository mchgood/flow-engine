package io.github.mchgood.flow.spi;

import io.github.mchgood.flow.node.FlowNode;

@FunctionalInterface
public interface NodeResolver { FlowNode resolve(String beanId); }
