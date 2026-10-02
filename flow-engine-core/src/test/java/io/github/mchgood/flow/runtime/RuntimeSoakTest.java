package io.github.mchgood.flow.runtime;

import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.result.FlowResult;
import io.github.mchgood.flow.result.NodeStatus;
import io.github.mchgood.flow.spi.CompiledCondition;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.SourceLocation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 有界持续并发验证：独立核对每次结果、业务调用次数与关闭后的工作线程退出。
 * <p>通过 production-verification profile 执行。延迟是带 JaCoCo 的环境观察值，不是 JMH 基准或 SLA。
 */
@Tag("soak")
@Timeout(120)
class RuntimeSoakTest {
    private static final ConditionEvaluator NO_CONDITIONS = new ConditionEvaluator() {
        @Override
        public CompiledCondition parse(String text, SourceLocation location) {
            throw new UnsupportedOperationException("No gateways in this test");
        }

        @Override
        public boolean evaluate(CompiledCondition expression, io.github.mchgood.flow.node.NodeContext context) {
            throw new UnsupportedOperationException("No gateways in this test");
        }
    };

    @Test
    void concurrentExecutionsRemainIsolatedAndWorkersExit() throws Exception {
        int callersCount = Integer.getInteger("flow.soak.callers", 16);
        int iterations = Integer.getInteger("flow.soak.iterations", 2000);
        assertTrue(callersCount >= 1 && callersCount <= 32);
        assertTrue(iterations >= 1 && iterations <= 10000);
        AtomicInteger calls = new AtomicInteger();
        Set<Thread> workers = ConcurrentHashMap.newKeySet();
        FlowNode<Integer> work = context -> {
            workers.add(Thread.currentThread());
            calls.incrementAndGet();
            int input = context.input(Integer.class);
            return "work_after".equals(context.nodeId())
                ? context.ancestorValue("work_before", Integer.class) + 1 : input;
        };
        EngineConfig config = new EngineConfig(8, 256, 64, 8, 8, 128, 32,
            Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(20), Duration.ofSeconds(5));
        var callers = Executors.newFixedThreadPool(callersCount);
        long[] latencies = new long[callersCount * iterations];
        var engine = new DefaultFlowEngine(id -> work, NO_CONDITIONS, config);
        CountDownLatch start = new CountDownLatch(1);
        try {
            engine.registerAll(Map.of("child", graph("start([s]) --> work_child --> finish([f])"),
                "parent", graph("start([s]) --> work_before --> fork{\"+\"}\n"
                    + "fork --> work_left\nfork --> child_main[[\"child\"]]\n"
                    + "work_left --> join{\"+\"}\nchild_main --> join\n"
                    + "join --> work_after --> finish([f])")));
            List<java.util.concurrent.Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < callersCount; i++) {
                int caller = i;
                futures.add(callers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int j = 0; j < iterations; j++) {
                        int input = caller * iterations + j;
                        long began = System.nanoTime();
                        FlowResult result = engine.execute("parent", input);
                        latencies[input] = System.nanoTime() - began;
                        assertTrue(result.succeeded(), result.errors().toString());
                        assertEquals(input, result.results().get("work_left").value());
                        assertEquals(input + 1, result.results().get("work_after").value());
                        assertEquals(NodeStatus.SUCCEEDED, result.results().get("child_main").status());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(90, TimeUnit.SECONDS);
            }
            assertEquals(4 * callersCount * iterations, calls.get());
            Arrays.sort(latencies);
            Path report = Path.of("target", "soak-report.txt");
            Files.createDirectories(report.getParent());
            Files.writeString(report, "java=" + System.getProperty("java.version") + " callers=" + callersCount
                + " iterations=" + iterations + " executions=" + latencies.length + " nodeCalls=" + calls.get()
                + "\np50Millis=" + millis(latencies, 0.50) + " p95Millis=" + millis(latencies, 0.95)
                + " p99Millis=" + millis(latencies, 0.99) + "\n");
        } finally {
            start.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
            engine.close();
            for (Thread worker : workers) {
                worker.join(5000);
                assertFalse(worker.isAlive(), "Worker leaked: " + worker.getName());
            }
        }
    }

    @Test
    void wideGraphNearLimitCompletesEveryNodeOnce() {
        AtomicInteger calls = new AtomicInteger();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            body.append("start([s]) --> work_").append(i).append("\nwork_").append(i).
                append(" --> finish([f])\n");
        }
        try (var engine = new DefaultFlowEngine(id -> context -> calls.incrementAndGet(), NO_CONDITIONS,
                EngineConfig.defaults())) {
            engine.register("wide", graph(body.toString()));
            FlowResult result = engine.execute("wide", null);
            assertTrue(result.succeeded(), result.errors().toString());
            assertEquals(500, calls.get());
            assertEquals(502, result.results().size());
        }
    }

    private static double millis(long[] values, double percentile) {
        return values[(int) Math.ceil(values.length * percentile) - 1] / 1_000_000.0;
    }

    private static String graph(String body) {
        return "```mermaid\nflowchart TD\n" + body + "\n```";
    }
}
