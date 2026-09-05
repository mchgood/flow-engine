package io.github.mchgood.flow.result;

import java.util.Map;
public record ChildFlowResultView(String status, String executionId, Map<String,NodeRecord> results) {}
