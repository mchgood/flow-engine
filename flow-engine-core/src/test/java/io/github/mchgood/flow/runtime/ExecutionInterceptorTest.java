package io.github.mchgood.flow.runtime;

import io.github.mchgood.flow.api.FlowDescriptor;
import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.result.FlowResult;
import io.github.mchgood.flow.result.NodeStatus;
import io.github.mchgood.flow.spi.CompiledCondition;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.FlowExecutionInterceptor;
import io.github.mchgood.flow.spi.NodeExecutionInterceptor;
import io.github.mchgood.flow.spi.SourceLocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证流程级与节点级拦截器的触发范围、调用顺序、线程归属与异常语义。
 */
@Timeout(15)
class ExecutionInterceptorTest {
    private record Condition(String text) implements CompiledCondition {}

    private static final ConditionEvaluator CONDITIONS = new ConditionEvaluator() {
        public CompiledCondition parse(String text, SourceLocation location) {
            return new Condition(text);
        }

        public boolean evaluate(CompiledCondition expression, NodeContext context) {
            return Boolean.TRUE.equals(context.input(Map.class).get(((Condition) expression).text()));
        }
    };
    private static final String SERIAL = md("start([s]) --> work --> finish([f])");

    private static String md(String body) {
        return "```mermaid\nflowchart TD\n" + body + "\n```";
    }

    private static EngineConfig config(long nodeTimeoutMillis, long flowTimeoutMillis) {
        return new EngineConfig(2, 8, 1, 8, 8, 64, 16, Duration.ofMillis(nodeTimeoutMillis),
                Duration.ofMillis(100), Duration.ofMillis(flowTimeoutMillis), Duration.ofMillis(50));
    }

    private static DefaultFlowEngine engine(Map<String, FlowNode<?>> nodes, EngineConfig configValue,
            List<FlowExecutionInterceptor> flowHooks, List<NodeExecutionInterceptor> nodeHooks) {
        return new DefaultFlowEngine(nodes::get, CONDITIONS, configValue, flowHooks, nodeHooks);
    }

    private static List<String> names(Recorder recorder) {
        return recorder.events.stream().map(event -> event.substring(0, event.indexOf('@'))).toList();
    }

    private static long sequence(String event) {
        return Long.parseLong(event.substring(event.indexOf('#') + 1));
    }

    /**
     * 记录钩子事件与触发线程;事件形如 beforeFlow@main#17。
     */
    static class Recorder implements FlowExecutionInterceptor, NodeExecutionInterceptor {
        private static final AtomicLong SEQUENCE = new AtomicLong();
        final List<String> events = new CopyOnWriteArrayList<>();
        private final String prefix;

        Recorder(String prefix) {
            this.prefix = prefix;
        }

        Recorder() {
            this("");
        }

        private void record(String event) {
            events.add(prefix + event + "@" + Thread.currentThread().getName() + "#" + SEQUENCE.incrementAndGet());
        }

        public void beforeFlow(String flowId, String executionId, Object input) {
            record("beforeFlow:" + flowId);
        }

        public void afterFlow(FlowResult result) {
            record("afterFlow");
        }

        public void onSuccess(FlowResult result) {
            record("onSuccess");
        }

        public void onFailure(FlowResult result) {
            record("onFailure");
        }

        public void beforeNode(NodeContext context) {
            record("beforeNode:" + context.nodeId());
        }

        public void afterNode(NodeContext context, io.github.mchgood.flow.spi.NodeOutcome outcome) {
            record("afterNode:" + context.nodeId() + (outcome.succeeded() ? "" : ":failed"));
        }

        public void onSuccess(NodeContext context, Object value) {
            record("onSuccess:" + context.nodeId());
        }

        public void onFailure(NodeContext context, String errorCode, String message) {
            record("onFailure:" + context.nodeId());
        }
    }

    @Test
    void happyPathFiresFlowHooksOnCallerAndNodeHooksOnWorker() {
        Recorder recorder = new Recorder();
        String callerThread = Thread.currentThread().getName();
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) context -> "ok"), config(4000, 8000),
                List.of(recorder), List.of(recorder))) {
            flowEngine.register("serial", SERIAL);
            FlowResult result = flowEngine.execute("serial", Map.of());
            assertTrue(result.succeeded());
            List<String> names = names(recorder);
            assertEquals(Set.of("beforeFlow:serial", "beforeNode:work", "afterNode:work", "onSuccess:work",
                    "afterFlow", "onSuccess"), Set.copyOf(names));
            assertEquals(6, names.size());
            assertTrue(names.indexOf("beforeFlow:serial") < names.indexOf("beforeNode:work"));
            assertTrue(names.indexOf("afterFlow") < names.indexOf("onSuccess"));
            for (String event : recorder.events) {
                boolean flowHook = event.startsWith("beforeFlow") || event.startsWith("afterFlow")
                        || event.startsWith("onSuccess@") || event.startsWith("onFailure@");
                if (flowHook) {
                    assertTrue(event.contains("@" + callerThread + "#"), event);
                } else {
                    assertTrue(event.contains("@flow-worker-"), event);
                }
            }
        }
    }

    @Test
    void beforeFlowReceivesTheTerminalExecutionId() {
        AtomicReference<String> seen = new AtomicReference<>();
        FlowExecutionInterceptor interceptor = new FlowExecutionInterceptor() {
            public void beforeFlow(String flowId, String executionId, Object input) {
                seen.set(executionId);
            }
        };
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) context -> 1), config(4000, 8000),
                List.of(interceptor), List.of())) {
            flowEngine.register("serial", SERIAL);
            FlowResult result = flowEngine.execute("serial", Map.of());
            assertEquals(result.executionId(), seen.get());
        }
    }

    @Test
    void failingFlowNotifiesAfterThenOnFailureWithoutSuccessHooks() {
        Recorder recorder = new Recorder();
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) context -> {
            throw new IllegalStateException("boom");
        }), config(4000, 8000), List.of(recorder), List.of(recorder))) {
            flowEngine.register("serial", SERIAL);
            FlowResult result = flowEngine.execute("serial", Map.of());
            assertFalse(result.succeeded());
            List<String> names = names(recorder);
            assertTrue(names.indexOf("beforeFlow:serial") < names.indexOf("afterFlow"));
            assertTrue(names.indexOf("afterFlow") < names.indexOf("onFailure"));
            assertTrue(names.contains("afterNode:work:failed"));
            assertTrue(names.contains("onFailure:work"));
            assertFalse(names.contains("onSuccess"));
            assertFalse(names.contains("onSuccess:work"));
        }
    }

    @Test
    void beforeFlowFailureAbortsExecutionAndReleasesAdmission() {
        AtomicBoolean first = new AtomicBoolean(true);
        FlowExecutionInterceptor abortOnce = new FlowExecutionInterceptor() {
            public void beforeFlow(String flowId, String executionId, Object input) {
                if (first.compareAndSet(true, false)) {
                    throw new IllegalStateException("blocked");
                }
            }
        };
        AtomicInteger executions = new AtomicInteger();
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) context -> executions.incrementAndGet()),
                config(4000, 8000), List.of(abortOnce), List.of())) {
            flowEngine.register("serial", SERIAL);
            FlowException failure = assertThrows(FlowException.class, () -> flowEngine.execute("serial", Map.of()));
            assertEquals("INTERCEPTOR_FAILED", failure.code());
            assertEquals(0, executions.get());
            assertTrue(flowEngine.execute("serial", Map.of()).succeeded());
            assertEquals(1, executions.get());
        }
    }

    @Test
    void beforeNodeFailureFailsNodeWithInterceptorCodeAndSkipsDownstream() {
        NodeExecutionInterceptor throwing = new NodeExecutionInterceptor() {
            public void beforeNode(NodeContext context) {
                throw new IllegalStateException("gate");
            }
        };
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) context -> 1), config(4000, 8000),
                List.of(), List.of(throwing))) {
            flowEngine.register("serial", SERIAL);
            FlowResult result = flowEngine.execute("serial", Map.of());
            assertFalse(result.succeeded());
            assertEquals("INTERCEPTOR_FAILED", result.errors().get(0).code());
            assertEquals(NodeStatus.FAILED, result.results().get("work").status());
            assertEquals(NodeStatus.SKIPPED, result.results().get("finish").status());
        }
    }

    @Test
    void afterNodeFailureIsLoggedAndOutcomeUnchanged() {
        NodeExecutionInterceptor throwing = new NodeExecutionInterceptor() {
            public void afterNode(NodeContext context, io.github.mchgood.flow.spi.NodeOutcome outcome) {
                throw new IllegalStateException("audit down");
            }
        };
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) context -> 1), config(4000, 8000),
                List.of(), List.of(throwing))) {
            flowEngine.register("serial", SERIAL);
            FlowResult result = flowEngine.execute("serial", Map.of());
            assertTrue(result.succeeded());
            assertEquals(NodeStatus.SUCCEEDED, result.results().get("work").status());
        }
    }

    @Test
    void afterFlowFailureIsLoggedAndResultStillReturned() {
        FlowExecutionInterceptor throwing = new FlowExecutionInterceptor() {
            public void afterFlow(FlowResult result) {
                throw new IllegalStateException("audit down");
            }
        };
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) context -> 1), config(4000, 8000),
                List.of(throwing), List.of())) {
            flowEngine.register("serial", SERIAL);
            FlowResult result = flowEngine.execute("serial", Map.of());
            assertTrue(result.succeeded());
        }
    }

    @Test
    void interceptorsRunInListOrderPerHook() {
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) context -> 1), config(4000, 8000),
                List.of(first, second), List.of(first, second))) {
            flowEngine.register("serial", SERIAL);
            flowEngine.execute("serial", Map.of());
            assertTrue(sequence(first.events.get(0)) < sequence(second.events.get(0)));
            assertTrue(seqOf(first, "beforeFlow") < seqOf(second, "beforeFlow"));
            assertTrue(seqOf(first, "afterFlow") < seqOf(second, "afterFlow"));
            assertTrue(seqOf(first, "beforeNode") < seqOf(second, "beforeNode"));
        }
    }

    private static long seqOf(Recorder recorder, String hook) {
        return sequence(recorder.events.stream().filter(event -> event.contains(hook)).findFirst().orElseThrow());
    }

    @Test
    void subflowTerminalDoesNotFireAdditionalFlowHooks() {
        Recorder recorder = new Recorder();
        String root = md("start([s]) --> mid --> child[[\"调用子流程\"]] --> finish([f])");
        String child = md("start([s]) --> inner --> finish([f])");
        try (var flowEngine = engine(Map.of("mid", (FlowNode<Object>) context -> 1,
                "inner", (FlowNode<Object>) context -> 2), config(4000, 8000), List.of(recorder),
                List.of(recorder))) {
            List<FlowDescriptor> registered = flowEngine.registerAll(Map.of("root", root, "child", child));
            assertEquals(2, registered.size());
            FlowResult result = flowEngine.execute("root", Map.of());
            assertTrue(result.succeeded());
            assertEquals(1, names(recorder).stream().filter(name -> name.startsWith("beforeFlow")).count());
            assertEquals(1, names(recorder).stream().filter(name -> name.startsWith("afterFlow")).count());
            assertTrue(names(recorder).contains("beforeNode:mid"));
            assertTrue(names(recorder).contains("beforeNode:inner"));
            assertTrue(names(recorder).contains("afterNode:inner"));
        }
    }

    @Test
    void gatewayNodesAreNotIntercepted() {
        Recorder recorder = new Recorder();
        String branch = md("start([s]) --> pick{\"x\"}\n    pick -->|\"yes\"| work\n    work --> join{\"X\"}\n"
                + "    pick -->|\"no\"| join{\"X\"}\n    join --> finish([f])");
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) context -> 1), config(4000, 8000),
                List.of(recorder), List.of(recorder))) {
            flowEngine.register("branch", branch);
            FlowResult result = flowEngine.execute("branch", Map.of("yes", true));
            assertTrue(result.succeeded());
            Set<String> hooked = Set.copyOf(recorder.events.stream().filter(name -> name.contains("Node:")).
                    map(name -> name.substring(name.indexOf(':') + 1, name.lastIndexOf('@'))).toList());
            assertEquals(Set.of("work"), hooked);
        }
    }

    @Test
    void timeoutSkipsUnstartedNodeHooksWhileRunningNodeGetsFailureOutcome() throws Exception {
        Recorder recorder = new Recorder();
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch nodeHooksSettled = new CountDownLatch(2);
        NodeExecutionInterceptor signal = new NodeExecutionInterceptor() {
            public void afterNode(NodeContext context, io.github.mchgood.flow.spi.NodeOutcome outcome) {
                nodeHooksSettled.countDown();
            }

            public void onFailure(NodeContext context, String errorCode, String message) {
                nodeHooksSettled.countDown();
            }
        };
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) context -> {
            block.await();
            return 1;
        }), config(60000, 250), List.of(), List.of(recorder, signal))) {
            flowEngine.register("serial", SERIAL);
            FlowResult result = flowEngine.execute("serial", Map.of());
            assertFalse(result.succeeded());
            assertTrue(nodeHooksSettled.await(5, TimeUnit.SECONDS));
            List<String> names = names(recorder);
            assertTrue(names.contains("beforeNode:work"));
            assertTrue(names.stream().anyMatch(name -> name.startsWith("afterNode:work:failed")));
            assertTrue(names.stream().noneMatch(name -> name.contains(":finish") || name.contains(":start")));
        } finally {
            block.countDown();
        }
    }

    @Test
    void beforeNodeDurationConsumesNodeTimeoutBudget() {
        NodeExecutionInterceptor slow = new NodeExecutionInterceptor() {
            public void beforeNode(NodeContext context) {
                try {
                    TimeUnit.MILLISECONDS.sleep(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        };
        AtomicInteger executions = new AtomicInteger();
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) context -> executions.incrementAndGet()),
                config(100, 8000), List.of(), List.of(slow))) {
            flowEngine.register("serial", SERIAL);
            FlowResult result = flowEngine.execute("serial", Map.of());
            assertFalse(result.succeeded());
            assertEquals(NodeStatus.TIMED_OUT, result.results().get("work").status());
            assertEquals(0, executions.get());
        }
    }

    @Test
    void emptyInterceptorListsBehaveLikeLegacyConstruction() {
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) context -> 1), config(4000, 8000),
                List.of(), List.of())) {
            flowEngine.register("serial", SERIAL);
            FlowResult result = flowEngine.execute("serial", Map.of());
            assertTrue(result.succeeded());
            assertEquals(1, result.results().get("work").value());
        }
    }

    @Test
    void flowInterceptorSeesCallerProvidedInput() {
        AtomicReference<Object> seen = new AtomicReference<>();
        FlowExecutionInterceptor interceptor = new FlowExecutionInterceptor() {
            public void beforeFlow(String flowId, String executionId, Object input) {
                seen.set(input);
            }
        };
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) NodeContext::input), config(4000, 8000),
                List.of(interceptor), List.of())) {
            flowEngine.register("serial", SERIAL);
            Map<String, Integer> input = Map.of("amount", 5);
            assertNotEquals(null, flowEngine.execute("serial", input).results().get("work").value());
            assertEquals(input, seen.get());
        }
    }

    @Test
    void nodeFailureOutcomeCarriesErrorCodeAndMessage() {
        Recorder recorder = new Recorder();
        try (var flowEngine = engine(Map.of("work", (FlowNode<Object>) context -> {
            throw new FlowException("BUSINESS_RULE", "amount too small");
        }), config(4000, 8000), List.of(recorder), List.of(recorder))) {
            flowEngine.register("serial", SERIAL);
            FlowResult result = flowEngine.execute("serial", Map.of());
            assertFalse(result.succeeded());
            assertTrue(names(recorder).contains("onFailure:work"));
            assertEquals("BUSINESS_RULE", result.results().get("work").error().code());
            assertNotNull(result.results().get("work").error().message());
            assertNull(result.results().get("work").value());
        }
    }
}
