package io.github.mchgood.flow.result;

import java.time.Instant;
public record NodeRecord(String nodeId, String targetId, String type, NodeStatus status,
    boolean present, Object value, String skipReason, FlowError error, Instant startedAt,
    Instant endedAt, String selectedEdgeId) {}
