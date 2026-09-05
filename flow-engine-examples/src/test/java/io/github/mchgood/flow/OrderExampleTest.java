package io.github.mchgood.flow;

import io.github.mchgood.flow.result.ChildFlowResultView;
import io.github.mchgood.flow.result.NodeStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OrderExampleTest {
    @Test void springOrderExampleWorks(){var r=OrderExample.run();assertTrue(r.succeeded(),r.errors().toString());assertEquals(NodeStatus.SKIPPED,r.results().get("recordReview").status());var child=(ChildFlowResultView)r.results().get("fulfillment_main").value();assertEquals(NodeStatus.SUCCEEDED,child.results().get("saveOrder").status());}
}
