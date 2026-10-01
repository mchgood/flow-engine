package io.github.mchgood.flow.runtime;

import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.result.FlowStatus;
import io.github.mchgood.flow.result.NodeStatus;
import io.github.mchgood.flow.spi.CompiledCondition;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.SourceLocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用受控扩展及同步器验证调度边界，不依赖 Spring 或 SpEL。 */
@Timeout(15)
class RuntimeBoundaryTest {
    private record Condition(String text) implements CompiledCondition {}
    private static final ConditionEvaluator CONDITIONS = new ConditionEvaluator() {
        public CompiledCondition parse(String text, SourceLocation location) {
            return new Condition(text);
        }
        public boolean evaluate(CompiledCondition expression, NodeContext context) {
            return Boolean.TRUE.equals(context.input(Map.class).get(((Condition) expression).text()));
        }
    };
    private static String md(String body) {
        return "```mermaid\nflowchart TD\n" + body + "\n```";
    }
    private static final String SERIAL = md("start([s]) --> work --> finish([f])");
    private static final String CHILD = md("start([s]) --> child_one[[\"child\"]] --> child_two[[\"child\"]]"
            + " --> finish([f])");
    private static EngineConfig config(int threads, int queue, int slots, int depth, int total, int children) {
        return new EngineConfig(threads, queue, 16, slots, depth, total, children, Duration.ofSeconds(4),
                Duration.ofSeconds(4), Duration.ofSeconds(8), Duration.ofMillis(50));
    }
    private static DefaultFlowEngine engine(Map<String, FlowNode<?>> nodes, EngineConfig config) {
        return new DefaultFlowEngine(nodes::get, CONDITIONS, config);
    }

    @Test
    void rejectionCancelsQueuedWorkAndCapacityRecovers() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var unwanted = new AtomicInteger();
        FlowNode<?> work = context -> {
            if ("block".equals(context.input())) {
                started.countDown();
                release.await();
            }
            return context.input();
        };
        var callers = Executors.newSingleThreadExecutor();
        try (var flowEngine = engine(Map.of("work", work, "queued", context -> unwanted.incrementAndGet()),
                config(1, 1, 4, 8, 64, 16))) {
            flowEngine.register("serial", SERIAL);
            flowEngine.register("flood", md("start([s]) --> queued_a\n"
                    + "start --> queued_b\n"
                    + "queued_a --> finish([f])\n"
                    + "queued_b --> finish"));
            var first = callers.submit(() -> flowEngine.execute("serial", "block"));
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var failed = flowEngine.execute("flood", null);
            assertEquals(FlowStatus.FAILED, failed.status());
            assertTrue(failed.errors().stream().anyMatch(exception -> exception.code().equals("RESOURCE_REJECTED")));
            assertEquals(0, unwanted.get());
            assertTrue(failed.physicalExitUnconfirmed().isEmpty());
            release.countDown();
            assertTrue(first.get(3, TimeUnit.SECONDS).succeeded());
            assertEquals("recovered", flowEngine.execute("serial", "recovered").results().get("work").value());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void nestedTasksShareAncestorInFlightLimit() {
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var calls = new AtomicInteger();
        var pairs = new CyclicBarrier(2);
        FlowNode<?> work = context -> {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            try {
                pairs.await(3, TimeUnit.SECONDS);
                calls.incrementAndGet();
                return context.executionId();
            } finally {
                active.decrementAndGet();
            }
        };
        try (var flowEngine = engine(Map.of("work", work), config(8, 32, 2, 8, 64, 16))) {
            StringBuilder parent = new StringBuilder("start([s]) --> fork{\"+\"}\n");
            for (int i = 0; i < 6; i++) {
                parent.append("fork --> child_").append(i).append("[[\"child\"]]\nchild_").append(i).
                        append(" --> join{\"+\"}\n");
            }
            parent.append("join --> finish([f])");
            flowEngine.registerAll(Map.of("parent", md(parent.toString()), "child", SERIAL));
            var result = flowEngine.execute("parent", null);
            assertTrue(result.succeeded(), result.errors().toString());
            assertEquals(6, calls.get());
            assertEquals(2, peak.get());
        }
    }

    @Test
    void totalChildLimitStopsLaterAliasWithoutReplayingEarlierCall() {
        var calls = new AtomicInteger();
        try (var flowEngine = engine(Map.of("work", context -> calls.incrementAndGet()),
                config(1, 8, 1, 8, 2, 16))) {
            flowEngine.registerAll(Map.of("root", CHILD, "child", SERIAL));
            var result = flowEngine.execute("root", null);
            assertEquals(1, calls.get());
            assertEquals(NodeStatus.SUCCEEDED, result.results().get("child_one").status());
            assertEquals(NodeStatus.FAILED, result.results().get("child_two").status());
            assertEquals("SUBFLOW_LIMIT_EXCEEDED", result.results().get("child_two").error().code());
        }
    }

    @Test
    void activeChildLimitIsEnforcedBeforeWorkersStart() {
        try (var flowEngine = engine(Map.of("work", context -> 1), config(2, 8, 2, 8, 64, 1))) {
            flowEngine.registerAll(Map.of("root", md("start([s]) --> child_one[[\"child\"]]\n"
                    + "start --> child_two[[\"child\"]]\n"
                    + "child_one --> finish([f])\n"
                    + "child_two --> finish"), "child", SERIAL));
            var result = flowEngine.execute("root", null);
            assertEquals(FlowStatus.FAILED, result.status());
            assertTrue(result.errors().stream().
                    anyMatch(exception -> exception.code().equals("SUBFLOW_LIMIT_EXCEEDED")));
        }
    }

    @Test
    void depthLimitRejectsWholeBatch() {
        try (var flowEngine = engine(Map.of("work", context -> 1), config(1, 8, 1, 0, 64, 16))) {
            assertEquals("SUBFLOW_LIMIT_EXCEEDED", assertThrows(FlowException.class,
                    () -> flowEngine.registerAll(Map.of("root", CHILD, "child", SERIAL))).code());
            assertEquals("FLOW_NOT_FOUND",
                    assertThrows(FlowException.class, () -> flowEngine.execute("child", null)).code());
        }
    }

    @Test
    void concurrentDuplicateRegistrationsPublishExactlyOne() throws Exception {
        var barrier = new CyclicBarrier(2);
        var callers = Executors.newFixedThreadPool(2);
        try (var flowEngine = engine(Map.of("work", context -> 1), config(2, 8, 2, 8, 64, 16))) {
            Callable<String> attempt = () -> {
                barrier.await(3, TimeUnit.SECONDS);
                try {
                    flowEngine.register("same", SERIAL);
                    return "ok";
                } catch (FlowException exception) {
                    return exception.code();
                }
            };
            var first = callers.submit(attempt);
            var second = callers.submit(attempt);
            assertEquals(Set.of("ok", "DUPLICATE_FLOW"),
                    Set.of(first.get(4, TimeUnit.SECONDS), second.get(4, TimeUnit.SECONDS)));
            assertTrue(flowEngine.execute("same", null).succeeded());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void closingDuringCompilationPreventsPublication() throws Exception {
        var binding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var caller = Executors.newSingleThreadExecutor();
        var flowEngine = new DefaultFlowEngine(id -> {
            binding.countDown();
            try {
                assertTrue(release.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                throw new AssertionError(exception);
            }
            return context -> 1;
        }, CONDITIONS);
        try {
            var registration = caller.submit(() -> assertThrows(FlowException.class,
                    () -> flowEngine.register("late", SERIAL)).code());
            assertTrue(binding.await(3, TimeUnit.SECONDS));
            flowEngine.close();
            release.countDown();
            assertEquals("ENGINE_CLOSED", registration.get(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            flowEngine.close();
            caller.shutdownNow();
        }
    }

    @Test
    void closeFromBusinessNodeFailsWithoutClosingTheEngine() {
        var ref = new AtomicReference<DefaultFlowEngine>();
        try (var flowEngine = engine(Map.of("work", context -> {
            if (Boolean.TRUE.equals(context.input())) {
                ref.get().close();
            }
            return 7;
        }), config(1, 8, 1, 8, 64, 16))) {
            ref.set(flowEngine);
            flowEngine.register("flow", SERIAL);
            assertEquals("REENTRANT_EXECUTION", flowEngine.execute("flow", true).errors().get(0).code());
            assertEquals(7, flowEngine.execute("flow", false).results().get("work").value());
        }
    }

    @Test
    void customNodeErrorCodeAndCallPathSurviveChildPropagation() {
        try (var flowEngine = engine(Map.of("work", context -> {
            throw new FlowException("BUSINESS_REJECTED", "denied");
        }), config(2, 8, 2, 8, 64, 16))) {
            flowEngine.registerAll(Map.of("root", CHILD, "child", SERIAL));
            var result = flowEngine.execute("root", null);
            var original = result.errors().stream().
                    filter(exception -> exception.code().equals("BUSINESS_REJECTED")).
                    findFirst().orElseThrow();
            assertEquals("work", original.nodeId());
            assertEquals("root/child_one:child", original.callPath());
            assertNotEquals(result.executionId(), original.executionId());
            assertEquals(NodeStatus.SKIPPED, result.results().get("child_two").status());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exclusiveBranchContainingParallelRegionJoinsExactlyOnce(boolean selected) {
        var count = new ConcurrentHashMap<String, AtomicInteger>();
        FlowNode<?> work = context -> {
            count.computeIfAbsent(context.nodeId(), k -> new AtomicInteger()).incrementAndGet();
            return context.nodeId();
        };
        String graph = "start([s]) --> choose{\"choose\"}\n"
                + "choose -->|\"selected\"| fork{\"+\"}\n"
                + "choose -->|\"default\"| work_else\n"
                + "fork --> work_left\n"
                + "fork --> work_right\n"
                + "work_left --> join{\"+\"}\n"
                + "work_right --> join\n"
                + "join --> merge{\"X\"}\n"
                + "work_else --> merge\n"
                + "merge --> work_end --> finish([f])";
        try (var flowEngine = engine(Map.of("work", work), config(4, 16, 4, 8, 64, 16))) {
            flowEngine.register("flow", md(graph));
            var result = flowEngine.execute("flow", Map.of("selected", selected));
            assertTrue(result.succeeded(), result.errors().toString());
            assertEquals(selected ? Set.of("work_left", "work_right", "work_end") : Set.of("work_else", "work_end"),
                    count.keySet());
            assertTrue(count.values().stream().allMatch(counter -> counter.get() == 1));
            assertEquals(selected ? NodeStatus.SUCCEEDED : NodeStatus.SKIPPED,
                    result.results().get("join").status());
        }
    }

    @Test
    void gatewayTimeoutDoesNotStartAnyBranchAndEngineRecovers() {
        ConditionEvaluator slow = new ConditionEvaluator() {
            @Override
            public CompiledCondition parse(String text, SourceLocation location) {
                return new Condition(text);
            }
            @Override
            public boolean evaluate(CompiledCondition condition, NodeContext context) {
                try {
                    new CountDownLatch(1).await();
                    return true;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new FlowException("INTERRUPTED", "stopped", exception);
                }
            }
        };
        var cfg = new EngineConfig(1, 8, 2, 1, 8, 64, 16, Duration.ofSeconds(2), Duration.ofMillis(200),
                Duration.ofSeconds(4), Duration.ofMillis(50));
        var calls = new AtomicInteger();
        try (var flowEngine = new DefaultFlowEngine(id -> context -> calls.incrementAndGet(), slow, cfg)) {
            flowEngine.register("flow", md("start([s]) --> choose{\"c\"}\n"
                    + "choose -->|\"first\"| work_a\n"
                    + "choose -->|\"default\"| work_b\n"
                    + "work_a --> merge{\"X\"}\n"
                    + "work_b --> merge\n"
                    + "merge --> finish([f])"));
            var result = flowEngine.execute("flow", null);
            assertEquals(FlowStatus.FAILED, result.status());
            assertEquals(NodeStatus.TIMED_OUT, result.results().get("choose").status());
            assertEquals(0, calls.get());
            assertTrue(result.physicalExitUnconfirmed().isEmpty());
            flowEngine.register("serial", SERIAL);
            assertTrue(flowEngine.execute("serial", null).succeeded());
        }
    }
}
