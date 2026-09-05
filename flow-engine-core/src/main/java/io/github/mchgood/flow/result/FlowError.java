package io.github.mchgood.flow.result;

public record FlowError(String code, String message, String executionId, String nodeId, String callPath) {}
