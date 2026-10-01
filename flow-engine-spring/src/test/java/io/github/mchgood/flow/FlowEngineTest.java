package io.github.mchgood.flow;

import io.github.mchgood.flow.api.ExecutionOptions;
import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.result.ChildFlowResultView;
import io.github.mchgood.flow.result.FlowResult;
import io.github.mchgood.flow.result.FlowStatus;
import io.github.mchgood.flow.result.NodeStatus;
import io.github.mchgood.flow.runtime.DefaultFlowEngine;
import io.github.mchgood.flow.spring.SpelConditionEvaluator;
import io.github.mchgood.flow.spring.SpringNodeResolver;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.GenericApplicationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心流程行为与 Spring 适配集成测试，覆盖解析、并发、失败、上下文与生命周期。
 */
@Timeout(8)
class FlowEngineTest {
    static String md(String body) { return "# Flow\n\n```mermaid\nflowchart TD\n" + body + "\n```\n"; }

    static EngineConfig config(int threads, int inFlight, Duration nodeTimeout, Duration flowTimeout) {
        return new EngineConfig(threads, 16, 8, inFlight, 8, 64, 16, nodeTimeout, nodeTimeout, flowTimeout,
                Duration.ofMillis(20));
    }

    static DefaultFlowEngine engine(Map<String, FlowNode<?>> beans) { return engine(beans, 4, 8); }

    static DefaultFlowEngine engine(Map<String, FlowNode<?>> beans, int threads, int inFlight) {
        return new DefaultFlowEngine(id -> {
            var node = beans.get(id);
            if (node == null) {
                throw new FlowException("BEAN_NOT_FOUND", id);
            }
            return node;
        }, new SpelConditionEvaluator(), config(threads, inFlight, Duration.ofSeconds(2), Duration.ofSeconds(3)));
    }

    @Test
    void aliasCallsAndAncestorResults() {
        List<String> calls = new CopyOnWriteArrayList<>();
        FlowNode<?> check = context -> {
            calls.add(context.nodeId());
            return context.nodeId();
        };
        FlowNode<?> save = context -> {
            assertEquals("check_before", context.ancestorValue("check_before", String.class));
            return null;
        };
        try (var flowEngine = engine(Map.of("check", check, "save", save))) {
            flowEngine.register("order", md("start([开始]) --> check_before[\"before\"] --> save[\"save\"]"
                    + " --> check_after[\"after\"] --> finish([结束])"));
            var result = flowEngine.execute("order", Map.of());
            assertTrue(result.succeeded(), result.errors().toString());
            assertEquals(List.of("check_before", "check_after"), calls);
            assertTrue(result.results().get("save").present());
            assertNull(result.results().get("save").value());
        }
    }

    @Test
    void parallelReallyOverlapsAndJoins() {
        var barrier = new CyclicBarrier(2);
        var count = new AtomicInteger();
        FlowNode<?> work = context -> {
            barrier.await(1, TimeUnit.SECONDS);
            count.incrementAndGet();
            return context.nodeId();
        };
        try (var flowEngine = engine(Map.of("a", work, "b", work, "endTask", context -> {
            assertEquals(2, count.get());
            return "ok";
        }))) {
            flowEngine.register("parallel", md("start([开始]) --> fork{\"+\"}\n"
                    + "fork --> a[\"a\"]\n"
                    + "fork --> b[\"b\"]\n"
                    + "a --> join{\"+\"}\n"
                    + "b --> join\n"
                    + "join --> endTask[\"end\"] --> finish([结束])"));
            assertTrue(flowEngine.execute("parallel", null).succeeded());
        }
    }

    @Test
    void dependencyReadyDoesNotWaitForUnrelatedBranch() {
        var enrichDone = new CountDownLatch(1);
        try (var flowEngine = engine(Map.of("slow", context -> {
            assertTrue(enrichDone.await(1, TimeUnit.SECONDS));
            return 1;
        }, "fast", context -> 1, "enrich", context -> {
            enrichDone.countDown();
            return 2;
        }))) {
            flowEngine.register("dag", md("start([开始]) --> slow[\"slow\"]\n"
                    + "start --> fast[\"fast\"] --> enrich[\"enrich\"]\n"
                    + "slow --> finish([结束])\n"
                    + "enrich --> finish"));
            assertTrue(flowEngine.execute("dag", null).succeeded());
        }
    }

    static String conditional(String first, String second) {
        return md("start([开始]) --> gate{\"choose\"}\n"
                + "gate -->|\"" + first + "\"| yes[\"yes\"]\n"
                + "gate -->|\"" + second + "\"| no[\"no\"]\n"
                + "yes --> merge{\"X\"}\n"
                + "no --> merge\n"
                + "merge --> finish([结束])");
    }

    @Test
    void spelAndDefaultBranches() {
        try (var flowEngine = engine(Map.of("yes", context -> "yes", "no", context -> "no"))) {
            flowEngine.register("choice", conditional("#input.amount <= 1000", "default"));
            var lowAmount = flowEngine.execute("choice", Map.of("amount", 10));
            assertTrue(lowAmount.succeeded(), lowAmount.errors().toString());
            assertEquals(NodeStatus.SUCCEEDED, lowAmount.results().get("yes").status());
            assertEquals("BRANCH_NOT_SELECTED", lowAmount.results().get("no").skipReason());
            var highAmount = flowEngine.execute("choice", Map.of("amount", 2000));
            assertTrue(highAmount.succeeded());
            assertEquals(NodeStatus.SUCCEEDED, highAmount.results().get("no").status());
        }
    }

    @Test
    void conditionsReadCompletedAncestor() {
        try (var flowEngine = engine(Map.of("check", context -> Map.of("passed", true),
                "yes", context -> 1, "no", context -> 2))) {
            String graph = conditional("#results['check'].present and #results['check'].value.passed", "default")
                    .replace("start([开始]) --> gate", "start([开始]) --> check[\"check\"] --> gate");
            flowEngine.register("choice", graph);
            var result = flowEngine.execute("choice", null);
            assertTrue(result.succeeded(), result.errors().toString());
            assertEquals(NodeStatus.SUCCEEDED, result.results().get("yes").status());
        }
    }

    @Test
    void conflictingConditionsNeverRunTasks() {
        var calls = new AtomicInteger();
        try (var flowEngine = engine(Map.of("yes", context -> calls.incrementAndGet(),
                "no", context -> calls.incrementAndGet()))) {
            flowEngine.register("choice", conditional("true", "true"));
            var result = flowEngine.execute("choice", null);
            assertFalse(result.succeeded());
            assertEquals("CONDITION_CONFLICT", result.errors().get(0).code());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void allFalseWithoutDefaultFails() {
        try (var flowEngine = engine(Map.of("yes", context -> 1, "no", context -> 2))) {
            flowEngine.register("choice", conditional("false", "false"));
            assertEquals("NO_MATCHING_BRANCH", flowEngine.execute("choice", null).errors().get(0).code());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "1", "'true'"})
    void rejectsNonBoolean(String expr) {
        try (var flowEngine = engine(Map.of("yes", context -> 1, "no", context -> 2))) {
            flowEngine.register("choice", conditional(expr, "default"));
            assertEquals("EXPRESSION_TYPE_ERROR", flowEngine.execute("choice", null).errors().get(0).code());
        }
    }

    @Test
    void exceptionDoesNotUseDefault() {
        try (var flowEngine = engine(Map.of("yes", context -> 1, "no", context -> 2))) {
            flowEngine.register("choice", conditional("#input.missing.foo > 0", "default"));
            assertFalse(flowEngine.execute("choice", Map.of()).succeeded());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"T(java.lang.System).exit(0)", "@service.call()", "new java.lang.String('x')",
            "#input.clear()", "#input['amount'] = 7", "#input.amount++", "#input.class", "#root",
            "#results[#input.key].present"})
    void rejectsForbiddenSpel(String expr) {
        try (var flowEngine = engine(Map.of("yes", context -> 1, "no", context -> 2))) {
            assertThrows(FlowException.class, () -> flowEngine.register("choice", conditional(expr, "default")));
        }
    }

    @Test
    void unknownAncestorRejectedAtEvaluation() {
        try (var flowEngine = engine(Map.of("yes", context -> 1, "no", context -> 2))) {
            flowEngine.register("choice", conditional("#results['no'].present", "default"));
            assertFalse(flowEngine.execute("choice", null).succeeded());
        }
    }

    @Test
    void duplicateDefaultsFail() {
        try (var flowEngine = engine(Map.of("yes", context -> 1, "no", context -> 2))) {
            assertEquals("MULTIPLE_DEFAULTS", assertThrows(FlowException.class,
                    () -> flowEngine.register("choice", conditional("default", "default"))).code());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a_", "a__before", "_before"})
    void invalidAlias(String id) {
        try (var flowEngine = engine(Map.of("a", context -> 1))) {
            assertThrows(FlowException.class, () -> flowEngine.register("flow", md(
                    "start([开始]) --> " + id + "[\"a\"] --> finish([结束])")));
        }
    }

    @Test
    void cyclesAndDuplicateEdgesFail() {
        try (var flowEngine = engine(Map.of("a", context -> 1, "b", context -> 1))) {
            assertThrows(FlowException.class, () -> flowEngine.register("flow", md(
                    "start([开始]) --> a --> b --> a\nb --> finish([结束])")));
            assertThrows(FlowException.class, () -> flowEngine.register("flow", md(
                    "start([开始]) --> a --> finish([结束])\nstart --> a")));
        }
    }

    @Test
    void labelsContainingArrowAreNotSplit() {
        try (var flowEngine = engine(Map.of("a", context -> 1))) {
            flowEngine.register("flow", md("start([开始]) --> a[\"a --> value\"] --> finish([结束])"));
            assertTrue(flowEngine.execute("flow", null).succeeded());
        }
    }

    @Test
    void malformedLabelHasLocation() {
        try (var flowEngine = engine(Map.of("a", context -> 1))) {
            var exception = assertThrows(FlowException.class, () -> flowEngine.register("flow",
                    md("start([开始]) --> a[\"unfinished")));
            assertTrue(exception.getMessage().contains("flow:5:"));
        }
    }

    @Test
    void markdownDefinitionContractIsStrict() {
        try (var flowEngine = engine(Map.of("a", context -> 1))) {
            assertEquals("MERMAID_BLOCK_COUNT",
                    assertThrows(FlowException.class, () -> flowEngine.register("flow", "# no graph")).code());
            assertEquals("MERMAID_BLOCK_COUNT", assertThrows(FlowException.class, () -> flowEngine.register("flow",
                    md("start([开始]) --> a --> finish([结束])")
                            + md("start([开始]) --> a --> finish([结束])"))).code());
            assertEquals("INVALID_HEADER", assertThrows(FlowException.class, () -> flowEngine
                    .register("flow", "```mermaid\ngraph TD\nstart([开始]) --> a --> finish([结束])\n```")).code());
        }
    }

    @Test
    void endpointAndReachabilityValidation() {
        try (var flowEngine = engine(Map.of("a", context -> 1, "b", context -> 1))) {
            assertEquals("INVALID_ENDPOINTS", assertThrows(FlowException.class,
                    () -> flowEngine.register("flow", md("a --> finish([结束])"))).code());
            assertEquals("INVALID_ENDPOINTS", assertThrows(FlowException.class,
                    () -> flowEngine.register("flow", md("start([开始]) --> a"))).code());
            assertEquals("UNREACHABLE_NODE", assertThrows(FlowException.class,
                    () -> flowEngine.register("flow",
                            md("start([开始]) --> a --> finish([结束])\nb --> finish"))).code());
        }
    }

    @Test
    void gatewayShapeAndDegreeValidation() {
        try (var flowEngine = engine(Map.of("a", context -> 1, "b", context -> 1))) {
            assertEquals("GATEWAY_DEGREE", assertThrows(FlowException.class, () -> flowEngine.register("flow",
                    md("start([开始]) --> gate{\"+\"} --> a --> finish([结束])"))).code());
            assertEquals("MISSING_CONDITION", assertThrows(FlowException.class, () -> flowEngine.register("flow",
                    md("start([开始]) --> gate{\"choose\"}\n"
                            + "gate --> a\n"
                            + "gate -->|\"default\"| b\n"
                            + "a --> join{\"X\"}\n"
                            + "b --> join\n"
                            + "join --> finish([结束])"))).code());
            assertEquals("CONDITION_NOT_ALLOWED", assertThrows(FlowException.class, () -> flowEngine
                    .register("flow", md("start([开始]) --> a\na -->|\"true\"| finish([结束])"))).code());
        }
    }

    @Test
    void duplicateFlowRegistrationIsAtomic() {
        try (var flowEngine = engine(Map.of("a", context -> 1))) {
            flowEngine.register("one", md("start([开始]) --> a --> finish([结束])"));
            assertEquals("DUPLICATE_FLOW", assertThrows(FlowException.class, () -> flowEngine.registerAll(
                    Map.of("one", md("start([开始]) --> a --> finish([结束])"),
                    "two", md("start([开始]) --> a --> finish([结束])")))).code());
            assertEquals("FLOW_NOT_FOUND", assertThrows(FlowException.class,
                    () -> flowEngine.execute("two", null)).code());
            assertTrue(flowEngine.execute("one", null).succeeded());
        }
    }

    @Test
    void closedEngineRejectsRegistrationAndExecution() {
        var flowEngine = engine(Map.of("a", context -> 1));
        flowEngine.register("flow", md("start([开始]) --> a --> finish([结束])"));
        flowEngine.close();
        assertEquals("ENGINE_CLOSED", assertThrows(FlowException.class,
                () -> flowEngine.register("next", md("start([开始]) --> a --> finish([结束])"))).code());
        assertEquals("ENGINE_CLOSED",
                assertThrows(FlowException.class, () -> flowEngine.execute("flow", null)).code());
    }

    @Test
    void singletonProxyIsPreserved() {
        try (var ctx = new GenericApplicationContext()) {
            var invoked = new AtomicInteger();
            ProxyFactory proxyFactory = new ProxyFactory((FlowNode<?>) context -> "ok");
            proxyFactory.addAdvice((MethodInterceptor) invocation -> {
                invoked.incrementAndGet();
                return invocation.proceed();
            });
            ctx.getBeanFactory().registerSingleton("work", proxyFactory.getProxy());
            ctx.refresh();
            try (var flowEngine = new DefaultFlowEngine(new SpringNodeResolver(ctx.getBeanFactory()),
                    new SpelConditionEvaluator())) {
                flowEngine.register("flow", md("start([开始]) --> work --> finish([结束])"));
                assertTrue(flowEngine.execute("flow", null).succeeded());
                assertEquals(1, invoked.get());
            }
        }
    }

    @Test
    void subflowsCompleteWithOneWorkerAndOneSlot() {
        try (var flowEngine = engine(Map.of("work", context -> {
            assertEquals("payload", context.input(String.class));
            return context.executionId();
        }), 1, 1)) {
            flowEngine.registerAll(Map.of("root",
                    md("start([开始]) --> child_before[[\"child\"]] --> child_after[[\"child\"]] --> finish([结束])"),
                    "child", md("start([开始]) --> leaf[[\"leaf\"]] --> finish([结束])"),
                    "leaf", md("start([开始]) --> work --> finish([结束])")));
            var result = flowEngine.execute("root", "payload");
            assertTrue(result.succeeded(), result.errors().toString());
            var before = (ChildFlowResultView) result.results().get("child_before").value();
            var after = (ChildFlowResultView) result.results().get("child_after").value();
            assertNotEquals(before.executionId(), after.executionId());
            assertFalse(result.results().containsKey("work"));
        }
    }

    @Test
    void childCannotReadParentResults() {
        try (var flowEngine = engine(Map.of("parentTask", context -> 1, "childTask", context -> {
            assertThrows(FlowException.class, () -> context.ancestorOutput("parentTask"));
            return 2;
        }))) {
            flowEngine.registerAll(Map.of(
                    "root", md("start([开始]) --> parentTask --> child[[\"child\"]] --> finish([结束])"),
                    "child", md("start([开始]) --> childTask --> finish([结束])")));
            assertTrue(flowEngine.execute("root", null).succeeded());
        }
    }

    @Test
    void childFailurePropagates() {
        try (var flowEngine = engine(Map.of("work", context -> {
            throw new IllegalStateException("broken");
        }))) {
            flowEngine.registerAll(Map.of(
                    "root", md("start([开始]) --> child[[\"child\"]] --> finish([结束])"),
                    "child", md("start([开始]) --> work --> finish([结束])")));
            var result = flowEngine.execute("root", null);
            assertEquals(NodeStatus.FAILED, result.results().get("child").status());
            assertTrue(result.errors().stream()
                    .anyMatch(exception -> exception.code().equals("CHILD_FLOW_FAILED")));
        }
    }

    @Test
    void childReferencesCheckedAtomically() {
        try (var flowEngine = engine(Map.of("work", context -> 1))) {
            assertThrows(FlowException.class, () -> flowEngine.registerAll(Map.of(
                    "a", md("start([开始]) --> b[[\"b\"]] --> finish([结束])"),
                    "b", md("start([开始]) --> a[[\"a\"]] --> finish([结束])"))));
            assertEquals("FLOW_NOT_FOUND", assertThrows(FlowException.class,
                    () -> flowEngine.execute("a", null)).code());
            assertEquals("SUBFLOW_NOT_FOUND", assertThrows(FlowException.class, () -> flowEngine.register("a",
                    md("start([开始]) --> missing[[\"missing\"]] --> finish([结束])"))).code());
        }
    }

    @Test
    void failureSkipsQueuedWork() {
        var count = new AtomicInteger();
        try (var flowEngine = engine(Map.of("a", context -> {
            throw new Exception("bad");
        }, "b", context -> count.incrementAndGet()), 1, 8)) {
            flowEngine.register("flow", md(
                    "start([开始]) --> a\nstart --> b\na --> finish([结束])\nb --> finish"));
            var result = flowEngine.execute("flow", null);
            assertFalse(result.succeeded());
            assertEquals(0, count.get());
        }
    }

    @Test
    void concurrentExecutionsAreIsolated() throws Exception {
        try (var flowEngine = engine(Map.of("work", context -> context.input(Integer.class)))) {
            flowEngine.register("flow", md("start([开始]) --> work --> finish([结束])"));
            var callers = Executors.newFixedThreadPool(4);
            try {
                var futures = new ArrayList<Future<FlowResult>>();
                for (int i = 0; i < 4; i++) {
                    int value = i;
                    futures.add(callers.submit(() -> flowEngine.execute("flow", value)));
                }
                for (int i = 0; i < 4; i++) {
                    assertEquals(i, futures.get(i).get().results().get("work").value());
                }
            } finally {
                callers.shutdownNow();
            }
        }
    }

    @Test
    void lateResultCannotOverwriteTimeout() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        FlowNode<?> stubborn = context -> {
            started.countDown();
            try {
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ignored) {
                    }
                }
                return "late";
            } finally {
                exited.countDown();
            }
        };
        var cfg = config(1, 1, Duration.ofMillis(40), Duration.ofMillis(100));
        try (var flowEngine = new DefaultFlowEngine(id -> stubborn, new SpelConditionEvaluator(), cfg)) {
            flowEngine.register("flow", md("start([开始]) --> work --> finish([结束])"));
            var callers = Executors.newSingleThreadExecutor();
            try {
                var future = callers.submit(() -> flowEngine.execute("flow", null));
                assertTrue(started.await(1, TimeUnit.SECONDS));
                var result = future.get(1, TimeUnit.SECONDS);
                assertEquals(FlowStatus.TIMED_OUT, result.status());
                assertFalse(result.physicalExitUnconfirmed().isEmpty());
                release.countDown();
                assertTrue(exited.await(1, TimeUnit.SECONDS));
                assertNull(result.results().get("work").value());
                assertEquals(NodeStatus.TIMED_OUT, result.results().get("work").status());
            } finally {
                release.countDown();
                callers.shutdownNow();
            }
        }
    }

    @Test
    void reentrantExecuteFailsInsteadOfDeadlock() {
        var ref = new AtomicReference<DefaultFlowEngine>();
        try (var flowEngine = engine(Map.of("work", context -> ref.get().execute("flow", null)), 1, 1)) {
            ref.set(flowEngine);
            flowEngine.register("flow", md("start([开始]) --> work --> finish([结束])"));
            assertEquals("REENTRANT_EXECUTION", flowEngine.execute("flow", null).errors().get(0).code());
        }
    }

    @Test
    void noActiveBranchStartsNestedSubflow() {
        var calls = new AtomicInteger();
        try (var flowEngine = engine(Map.of("yes", context -> 1, "work", context -> calls.incrementAndGet()))) {
            String parent = conditional("true", "default")
                    .replace("no[\"no\"]", "child[[\"child\"]]")
                    .replace("\nno --> merge", "\nchild --> merge");
            flowEngine.registerAll(Map.of("root", parent, "child",
                    md("start([开始]) --> work --> finish([结束])")));
            assertTrue(flowEngine.execute("root", null).succeeded());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void siblingResultsCannotLeakEvenIfCompleted() {
        var done = new CountDownLatch(1);
        try (var flowEngine = engine(Map.of("a", context -> {
            done.countDown();
            return 1;
        }, "b", context -> {
            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertThrows(FlowException.class, () -> context.ancestorOutput("a"));
            return 2;
        }))) {
            flowEngine.register("flow", md(
                    "start([开始]) --> a\nstart --> b\na --> finish([结束])\nb --> finish"));
            assertTrue(flowEngine.execute("flow", null).succeeded());
        }
    }

    @Test
    void skippedAncestorAndSuccessfulNullDiffer() {
        FlowNode<?> after = context -> {
            assertFalse(context.ancestorOutput("no").present());
            assertTrue(context.ancestorOutput("yes").present());
            assertNull(context.ancestorValue("yes", Object.class));
            return 1;
        };
        try (var flowEngine = engine(Map.of("yes", context -> null, "no", context -> 2, "after", after))) {
            flowEngine.register("choice",
                    conditional("true", "default").replace("merge --> finish", "merge --> after --> finish"));
            assertTrue(flowEngine.execute("choice", null).succeeded());
        }
    }

    @Test
    void nestedInactiveBranchDoesNotBlock() {
        var calls = new AtomicInteger();
        try (var flowEngine = engine(Map.of("yes", context -> 1,
                "a", context -> calls.incrementAndGet(), "b", context -> calls.incrementAndGet()))) {
            String body = "start([开始]) --> outer{\"outer\"}\n"
                    + "outer -->|\"true\"| yes\n"
                    + "outer -->|\"default\"| inner{\"inner\"}\n"
                    + "inner -->|\"true\"| a\n"
                    + "inner -->|\"default\"| b\n"
                    + "a --> innerJoin{\"X\"}\n"
                    + "b --> innerJoin\n"
                    + "innerJoin --> outerJoin{\"X\"}\n"
                    + "yes --> outerJoin\n"
                    + "outerJoin --> finish([结束])";
            flowEngine.register("flow", md(body));
            assertTrue(flowEngine.execute("flow", null).succeeded());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void prototypeBeansRejected() {
        try (var ctx = new GenericApplicationContext()) {
            ctx.registerBean("work", FlowNode.class, () -> context -> 1,
                    definition -> definition.setScope("prototype"));
            ctx.refresh();
            try (var flowEngine = new DefaultFlowEngine(new SpringNodeResolver(ctx.getBeanFactory()),
                    new SpelConditionEvaluator())) {
                assertEquals("BEAN_SCOPE_UNSUPPORTED", assertThrows(FlowException.class,
                        () -> flowEngine.register("flow", md("start([开始]) --> work --> finish([结束])"))).code());
            }
        }
    }

    @Test
    void expressionErrorIsNotHiddenByOtherTrueCondition() {
        try (var flowEngine = engine(Map.of("yes", context -> 1, "no", context -> 2))) {
            flowEngine.register("flow", conditional("true", "#input.missing.invalid"));
            assertFalse(flowEngine.execute("flow", Map.of()).succeeded());
        }
    }

    @Test
    void parentDeadlineCancelsChild() {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try (var flowEngine = engine(Map.of("work", context -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw exception;
            }
            return 1;
        }))) {
            flowEngine.registerAll(Map.of(
                    "root", md("start([开始]) --> child[[\"child\"]] --> finish([结束])"),
                    "child", md("start([开始]) --> work --> finish([结束])")));
            var result = flowEngine.execute("root", null, ExecutionOptions.withTimeout(Duration.ofMillis(150)));
            assertEquals(FlowStatus.TIMED_OUT, result.status());
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertEquals(NodeStatus.TIMED_OUT, result.results().get("child").status());
        } catch (InterruptedException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void rootAdmissionRejectsWithoutWaiting() throws Exception {
        var start = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cfg = new EngineConfig(1, 1, 1, 1, 8, 64, 16, Duration.ofSeconds(2), Duration.ofSeconds(1),
                Duration.ofSeconds(3), Duration.ofMillis(20));
        try (var flowEngine = new DefaultFlowEngine(id -> context -> {
            start.countDown();
            release.await();
            return 1;
        }, new SpelConditionEvaluator(), cfg)) {
            flowEngine.register("flow", md("start([开始]) --> work --> finish([结束])"));
            var callers = Executors.newSingleThreadExecutor();
            try {
                var first = callers.submit(() -> flowEngine.execute("flow", null));
                assertTrue(start.await(1, TimeUnit.SECONDS));
                assertEquals("FLOW_REJECTED", assertThrows(FlowException.class,
                        () -> flowEngine.execute("flow", null)).code());
                release.countDown();
                assertTrue(first.get().succeeded());
            } finally {
                release.countDown();
                callers.shutdownNow();
            }
        }
    }

    @Test
    void interruptedCallerReturnsFixedFailure() throws Exception {
        var start = new CountDownLatch(1);
        var result = new AtomicReference<FlowResult>();
        var flag = new AtomicBoolean();
        try (var flowEngine = engine(Map.of("work", context -> {
            start.countDown();
            new CountDownLatch(1).await();
            return 1;
        }))) {
            flowEngine.register("flow", md("start([开始]) --> work --> finish([结束])"));
            Thread caller = new Thread(() -> {
                result.set(flowEngine.execute("flow", null));
                flag.set(Thread.currentThread().isInterrupted());
            });
            caller.start();
            assertTrue(start.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(1000);
            assertFalse(caller.isAlive());
            assertEquals(FlowStatus.FAILED, result.get().status());
            assertTrue(flag.get());
        }
    }

    @Test
    void closeTerminatesActiveWorkAndRejectsNewCalls() throws Exception {
        var start = new CountDownLatch(1);
        var flowEngine = engine(Map.of("work", context -> {
            start.countDown();
            new CountDownLatch(1).await();
            return 1;
        }));
        flowEngine.register("flow", md("start([开始]) --> work --> finish([结束])"));
        var callers = Executors.newSingleThreadExecutor();
        try {
            var future = callers.submit(() -> flowEngine.execute("flow", null));
            assertTrue(start.await(1, TimeUnit.SECONDS));
            flowEngine.close();
            assertEquals(FlowStatus.FAILED, future.get(1, TimeUnit.SECONDS).status());
            assertEquals("ENGINE_CLOSED", assertThrows(FlowException.class,
                    () -> flowEngine.execute("flow", null)).code());
        } finally {
            flowEngine.close();
            callers.shutdownNow();
        }
    }
}
