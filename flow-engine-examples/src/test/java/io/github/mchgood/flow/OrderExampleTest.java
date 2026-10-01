package io.github.mchgood.flow;

import io.github.mchgood.flow.result.ChildFlowResultView;
import io.github.mchgood.flow.result.NodeStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证组合订单示例的可执行性。
 */
class OrderExampleTest {
    @Test
    void springOrderExampleWorks() {
        var result = OrderExample.run();
        assertTrue(result.succeeded(), result.errors().toString());
        assertEquals(NodeStatus.SKIPPED, result.results().get("recordReview").status());
        var child = (ChildFlowResultView) result.results().get("fulfillment_main").value();
        assertEquals(NodeStatus.SUCCEEDED, child.results().get("saveOrder").status());
    }
}
