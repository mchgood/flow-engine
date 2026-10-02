package io.github.mchgood.flow.runtime;

import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.result.FlowStatus;
import io.github.mchgood.flow.spi.CompiledCondition;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.NodeExecutionInterceptor;
import io.github.mchgood.flow.spi.NodeOutcome;
import io.github.mchgood.flow.spi.SourceLocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证任一实例失败后整棵执行树停止新业务，同时保留物理收尾和容量约束。 */
@Timeout(15)
class ExecutionTreeFailureTest {
    private static final ConditionEvaluator CONDITIONS = new ConditionEvaluator() {
        @Override
        public CompiledCondition parse(String expression, SourceLocation location) {
            throw new UnsupportedOperationException("No conditions in these graphs");
        }

        @Override
        public boolean evaluate(CompiledCondition expression, NodeContext context) {
            throw new UnsupportedOperationException("No conditions in these graphs");
        }
    };

    private static String md(String body) {
        return "```mermaid\nflowchart TD\n" + body + "\n```";
    }

    private static EngineConfig config(int threads, int queue, int roots) {
        return new EngineConfig(threads, queue, roots, 8, 8, 64, 16, Duration.ofSeconds(5),
                Duration.ofSeconds(1), Duration.ofSeconds(8), Duration.ofMillis(50));
    }

    @ParameterizedTest
    @CsvSource({"parent, business", "parent, hook", "child, business", "child, hook"})
    void failureStopsNestedAndSiblingBusinessButWaitsForPhysicalExit(String failureScope, String blockedStage)
            throws Exception {
        var entered = new CountDownLatch(1);
        var failed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var effects = new AtomicInteger();
        var holdCalls = new AtomicInteger();
        var holdOutcome = new AtomicReference<NodeOutcome>();
        NodeExecutionInterceptor hooks = new NodeExecutionInterceptor() {
            @Override
            public void beforeNode(NodeContext context) {
                if ("hold".equals(context.nodeId()) && "hook".equals(blockedStage)) {
                    entered.countDown();
                    awaitRelease(release);
                }
            }

            @Override
            public void afterNode(NodeContext context, NodeOutcome outcome) {
                if ("hold".equals(context.nodeId())) {
                    holdOutcome.set(outcome);
                }
            }

            @Override
            public void onFailure(NodeContext context, String errorCode, String message) {
                if ("boom".equals(context.nodeId())) {
                    failed.countDown();
                }
            }
        };
        Map<String, FlowNode<?>> nodes = Map.of(
                "hold", context -> {
                    holdCalls.incrementAndGet();
                    if ("business".equals(blockedStage)) {
                        entered.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    }
                    return "held";
                },
                "boom", context -> {
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                    throw new IllegalStateException("deliberate failure");
                },
                "effect", context -> effects.incrementAndGet());
        var callers = Executors.newSingleThreadExecutor();
        try (var engine = new DefaultFlowEngine(nodes::get, CONDITIONS, config(2, 8, 1), List.of(), List.of(hooks))) {
            String failingNode = "parent".equals(failureScope) ? "boom" : "broken[[\"broken\"]]";
            engine.registerAll(Map.of(
                    "root", md("start([s]) --> fork{\"+\"}\nfork --> healthy[[\"healthy\"]]\n"
                            + "fork --> " + failingNode + "\nhealthy --> join{\"+\"}\n"
                            + ("parent".equals(failureScope) ? "boom" : "broken")
                            + " --> join\njoin --> finish([f])"),
                    "healthy", md("start([s]) --> leaf[[\"leaf\"]] --> effect_parent"
                            + " --> leaf_later[[\"leaf\"]] --> finish([f])"),
                    "leaf", md("start([s]) --> hold --> effect_leaf --> finish([f])"),
                    "broken", md("start([s]) --> boom --> finish([f])")));
            var result = callers.submit(() -> engine.execute("root", null));
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            assertFalse(result.isDone(), "Logical failure must still wait for the running task or hook");
            assertEquals("FLOW_REJECTED", assertThrows(FlowException.class,
                    () -> engine.execute("leaf", null)).code());
            release.countDown();
            var completed = result.get(3, TimeUnit.SECONDS);
            assertEquals(FlowStatus.FAILED, completed.status());
            assertTrue(completed.errors().stream().anyMatch(error -> "NODE_FAILED".equals(error.code())));
            assertTrue(completed.errors().stream().anyMatch(error -> "FLOW_STOPPED".equals(error.code())));
            assertEquals(0, effects.get(), "No new business in descendants or sibling subflows after failure");
            assertEquals("business".equals(blockedStage) ? 1 : 0, holdCalls.get());
            assertEquals("business".equals(blockedStage), holdOutcome.get().succeeded());
            if ("hook".equals(blockedStage)) {
                assertEquals("FLOW_STOPPED", holdOutcome.get().errorCode());
            }
            assertTrue(completed.physicalExitUnconfirmed().isEmpty());
            assertTrue(engine.execute("leaf", null).succeeded(), "Admission and worker capacity recover");
            assertEquals(1, effects.get());
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void childQueueRejectionCancelsQueuedSiblingWithoutReleasingOtherRootTask() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var effects = new AtomicInteger();
        Map<String, FlowNode<?>> nodes = Map.of("hold", context -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "held";
        }, "effect", context -> effects.incrementAndGet());
        var callers = Executors.newFixedThreadPool(2);
        try (var engine = new DefaultFlowEngine(nodes::get, CONDITIONS, config(1, 1, 2), List.of(), List.of())) {
            engine.registerAll(Map.of(
                    "blocker", md("start([s]) --> hold --> finish([f])"),
                    "root", md("start([s]) --> fork{\"+\"}\nfork --> leaf_first[[\"leaf\"]]\n"
                            + "fork --> leaf_second[[\"leaf\"]]\nleaf_first --> join{\"+\"}\n"
                            + "leaf_second --> join\njoin --> finish([f])"),
                    "leaf", md("start([s]) --> effect --> finish([f])")));
            var blocker = callers.submit(() -> engine.execute("blocker", null));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var result = callers.submit(() -> engine.execute("root", null));
            // 队列拒绝发生于提交阶段，不触发节点钩子；根结果独立于其他根的阻塞任务返回。
            var completed = result.get(3, TimeUnit.SECONDS);
            assertEquals(FlowStatus.FAILED, completed.status());
            assertTrue(completed.errors().stream().anyMatch(error -> "RESOURCE_REJECTED".equals(error.code())));
            assertFalse(blocker.isDone());
            assertEquals(0, effects.get());
            assertTrue(completed.physicalExitUnconfirmed().isEmpty());
            release.countDown();
            assertTrue(blocker.get(3, TimeUnit.SECONDS).succeeded());
            assertTrue(engine.execute("leaf", null).succeeded());
            assertEquals(1, effects.get());
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    /** 有界等待前置钩子放行，保持普通失败允许已启动钩子物理收尾的约定。 */
    private static void awaitRelease(CountDownLatch release) {
        try {
            assertTrue(release.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
