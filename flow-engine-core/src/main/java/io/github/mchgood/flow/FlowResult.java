package io.github.mchgood.flow;

import java.time.Instant;
import java.util.*;
public record FlowResult(String executionId, String rootExecutionId, String parentExecutionId,
    String flowId, String definitionHash, FlowStatus status, Instant startedAt, Instant endedAt,
    Map<String,NodeRecord> results, List<FlowError> errors, List<String> physicalExitUnconfirmed) {
    public FlowResult {
        results=Collections.unmodifiableMap(new LinkedHashMap<>(results));
        errors=List.copyOf(errors); physicalExitUnconfirmed=List.copyOf(physicalExitUnconfirmed);
    }
    public boolean succeeded() { return status==FlowStatus.SUCCEEDED; }
}
