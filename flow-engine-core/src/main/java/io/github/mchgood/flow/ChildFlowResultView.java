package io.github.mchgood.flow;

import java.util.Map;
public record ChildFlowResultView(String status, String executionId, Map<String,NodeRecord> results) {}
