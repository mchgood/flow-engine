package io.github.mchgood.flow.runtime;

import io.github.mchgood.flow.api.ExecutionOptions;
import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.*;
import io.github.mchgood.flow.result.*;
import io.github.mchgood.flow.spi.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用受控扩展及同步器验证调度边界，不依赖 Spring 或 SpEL。 */
@Timeout(15)
class RuntimeBoundaryTest {
    private record Condition(String text) implements CompiledCondition {}
    private static final ConditionEvaluator CONDITIONS=new ConditionEvaluator() {
        public CompiledCondition parse(String text,SourceLocation location) {return new Condition(text);}
        public boolean evaluate(CompiledCondition expression,NodeContext context) {return Boolean.TRUE.equals(context.input(Map.class).get(((Condition)expression).text()));}
    };
    private static String md(String body) {return "```mermaid\nflowchart TD\n"+body+"\n```";}
    private static final String SERIAL=md("start([s]) --> work --> finish([f])");
    private static final String CHILD=md("start([s]) --> child_one[[\"child\"]] --> child_two[[\"child\"]] --> finish([f])");
    private static EngineConfig config(int threads,int queue,int slots,int depth,int total,int children) {
        return new EngineConfig(threads,queue,16,slots,depth,total,children,Duration.ofSeconds(4),Duration.ofSeconds(4),Duration.ofSeconds(8),Duration.ofMillis(50));
    }
    private static DefaultFlowEngine engine(Map<String,FlowNode<?>> nodes,EngineConfig config) {
        return new DefaultFlowEngine(nodes::get,CONDITIONS,config);
    }
    @Test void rejectionCancelsQueuedWorkAndCapacityRecovers() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var unwanted=new AtomicInteger();
        FlowNode<?> work=c->{if("block".equals(c.input())){started.countDown();release.await();}return c.input();};
        var callers=Executors.newSingleThreadExecutor();
        try(var e=engine(Map.of("work",work,"queued",c->unwanted.incrementAndGet()),config(1,1,4,8,64,16))) {
            e.register("serial",SERIAL);
            e.register("flood",md("start([s]) --> queued_a\nstart --> queued_b\nqueued_a --> finish([f])\nqueued_b --> finish"));
            var first=callers.submit(()->e.execute("serial","block"));
            assertTrue(started.await(3,TimeUnit.SECONDS));
            var failed=e.execute("flood",null);
            assertEquals(FlowStatus.FAILED,failed.status());
            assertTrue(failed.errors().stream().anyMatch(x->x.code().equals("RESOURCE_REJECTED")));
            assertEquals(0,unwanted.get());assertTrue(failed.physicalExitUnconfirmed().isEmpty());
            release.countDown();assertTrue(first.get(3,TimeUnit.SECONDS).succeeded());
            assertEquals("recovered",e.execute("serial","recovered").results().get("work").value());
        } finally {release.countDown();callers.shutdownNow();}
    }
    @Test void nestedTasksShareAncestorInFlightLimit() {
        var active=new AtomicInteger();var peak=new AtomicInteger();var calls=new AtomicInteger();var pairs=new CyclicBarrier(2);
        FlowNode<?> work=c->{int current=active.incrementAndGet();peak.accumulateAndGet(current,Math::max);try{pairs.await(3,TimeUnit.SECONDS);calls.incrementAndGet();return c.executionId();}finally{active.decrementAndGet();}};
        try(var e=engine(Map.of("work",work),config(8,32,2,8,64,16))) {
            StringBuilder parent=new StringBuilder("start([s]) --> fork{\"+\"}\n");
            for(int i=0;i<6;i++)parent.append("fork --> child_").append(i).append("[[\"child\"]]\nchild_").append(i).append(" --> join{\"+\"}\n");
            parent.append("join --> finish([f])");
            e.registerAll(Map.of("parent",md(parent.toString()),"child",SERIAL));
            var result=e.execute("parent",null);assertTrue(result.succeeded(),result.errors().toString());
            assertEquals(6,calls.get());assertEquals(2,peak.get());
        }
    }
    @Test void totalChildLimitStopsLaterAliasWithoutReplayingEarlierCall() {
        var calls=new AtomicInteger();
        try(var e=engine(Map.of("work",c->calls.incrementAndGet()),config(1,8,1,8,2,16))) {
            e.registerAll(Map.of("root",CHILD,"child",SERIAL));
            var r=e.execute("root",null);assertEquals(1,calls.get());
            assertEquals(NodeStatus.SUCCEEDED,r.results().get("child_one").status());
            assertEquals(NodeStatus.FAILED,r.results().get("child_two").status());
            assertEquals("SUBFLOW_LIMIT_EXCEEDED",r.results().get("child_two").error().code());
        }
    }
    @Test void activeChildLimitIsEnforcedBeforeWorkersStart() {
        try(var e=engine(Map.of("work",c->1),config(2,8,2,8,64,1))) {
            e.registerAll(Map.of("root",md("start([s]) --> child_one[[\"child\"]]\nstart --> child_two[[\"child\"]]\nchild_one --> finish([f])\nchild_two --> finish"),"child",SERIAL));
            var r=e.execute("root",null);assertEquals(FlowStatus.FAILED,r.status());
            assertTrue(r.errors().stream().anyMatch(x->x.code().equals("SUBFLOW_LIMIT_EXCEEDED")));
        }
    }
    @Test void depthLimitRejectsWholeBatch() {
        try(var e=engine(Map.of("work",c->1),config(1,8,1,0,64,16))) {
            assertEquals("SUBFLOW_LIMIT_EXCEEDED",assertThrows(FlowException.class,()->e.registerAll(Map.of("root",CHILD,"child",SERIAL))).code());
            assertEquals("FLOW_NOT_FOUND",assertThrows(FlowException.class,()->e.execute("child",null)).code());
        }
    }
    @Test void concurrentDuplicateRegistrationsPublishExactlyOne() throws Exception {
        var barrier=new CyclicBarrier(2);var callers=Executors.newFixedThreadPool(2);
        try(var e=engine(Map.of("work",c->1),config(2,8,2,8,64,16))) {
            Callable<String> attempt=()->{barrier.await(3,TimeUnit.SECONDS);try{e.register("same",SERIAL);return "ok";}catch(FlowException x){return x.code();}};
            var a=callers.submit(attempt);var b=callers.submit(attempt);
            assertEquals(Set.of("ok","DUPLICATE_FLOW"),Set.of(a.get(4,TimeUnit.SECONDS),b.get(4,TimeUnit.SECONDS)));
            assertTrue(e.execute("same",null).succeeded());
        } finally {callers.shutdownNow();}
    }
    @Test void closingDuringCompilationPreventsPublication() throws Exception {
        var binding=new CountDownLatch(1);var release=new CountDownLatch(1);var caller=Executors.newSingleThreadExecutor();
        var e=new DefaultFlowEngine(id->{binding.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException x){throw new AssertionError(x);}return c->1;},CONDITIONS);
        try {
            var registration=caller.submit(()->assertThrows(FlowException.class,()->e.register("late",SERIAL)).code());
            assertTrue(binding.await(3,TimeUnit.SECONDS));e.close();release.countDown();
            assertEquals("ENGINE_CLOSED",registration.get(3,TimeUnit.SECONDS));
        } finally {release.countDown();e.close();caller.shutdownNow();}
    }
    @Test void closeFromBusinessNodeFailsWithoutClosingTheEngine() {
        var ref=new AtomicReference<DefaultFlowEngine>();
        try(var e=engine(Map.of("work",c->{if(Boolean.TRUE.equals(c.input()))ref.get().close();return 7;}),config(1,8,1,8,64,16))) {
            ref.set(e);e.register("flow",SERIAL);
            assertEquals("REENTRANT_EXECUTION",e.execute("flow",true).errors().get(0).code());
            assertEquals(7,e.execute("flow",false).results().get("work").value());
        }
    }
    @Test void customNodeErrorCodeAndCallPathSurviveChildPropagation() {
        try(var e=engine(Map.of("work",c->{throw new FlowException("BUSINESS_REJECTED","denied");}),config(2,8,2,8,64,16))) {
            e.registerAll(Map.of("root",CHILD,"child",SERIAL));var r=e.execute("root",null);
            var original=r.errors().stream().filter(x->x.code().equals("BUSINESS_REJECTED")).findFirst().orElseThrow();
            assertEquals("work",original.nodeId());assertEquals("root/child_one:child",original.callPath());assertNotEquals(r.executionId(),original.executionId());
            assertEquals(NodeStatus.SKIPPED,r.results().get("child_two").status());
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void exclusiveBranchContainingParallelRegionJoinsExactlyOnce(boolean selected) {
        var count=new ConcurrentHashMap<String,AtomicInteger>();
        FlowNode<?> work=c->{count.computeIfAbsent(c.nodeId(),k->new AtomicInteger()).incrementAndGet();return c.nodeId();};
        String graph="start([s]) --> choose{\"choose\"}\nchoose -->|\"selected\"| fork{\"+\"}\nchoose -->|\"default\"| work_else\nfork --> work_left\nfork --> work_right\nwork_left --> join{\"+\"}\nwork_right --> join\njoin --> merge{\"X\"}\nwork_else --> merge\nmerge --> work_end --> finish([f])";
        try(var e=engine(Map.of("work",work),config(4,16,4,8,64,16))) {
            e.register("flow",md(graph));var r=e.execute("flow",Map.of("selected",selected));assertTrue(r.succeeded(),r.errors().toString());
            assertEquals(selected?Set.of("work_left","work_right","work_end"):Set.of("work_else","work_end"),count.keySet());
            assertTrue(count.values().stream().allMatch(n->n.get()==1));
            assertEquals(selected?NodeStatus.SUCCEEDED:NodeStatus.SKIPPED,r.results().get("join").status());
        }
    }
    @Test void gatewayTimeoutDoesNotStartAnyBranchAndEngineRecovers() {
        ConditionEvaluator slow=new ConditionEvaluator() {
            public CompiledCondition parse(String t,SourceLocation l){return new Condition(t);}
            public boolean evaluate(CompiledCondition c,NodeContext n){try{new CountDownLatch(1).await();return true;}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new FlowException("INTERRUPTED","stopped",ex);}}
        };
        var cfg=new EngineConfig(1,8,2,1,8,64,16,Duration.ofSeconds(2),Duration.ofMillis(200),Duration.ofSeconds(4),Duration.ofMillis(50));
        var calls=new AtomicInteger();try(var e=new DefaultFlowEngine(id->c->calls.incrementAndGet(),slow,cfg)) {
            e.register("flow",md("start([s]) --> choose{\"c\"}\nchoose -->|\"first\"| work_a\nchoose -->|\"default\"| work_b\nwork_a --> merge{\"X\"}\nwork_b --> merge\nmerge --> finish([f])"));
            var r=e.execute("flow",null);assertEquals(FlowStatus.FAILED,r.status());assertEquals(NodeStatus.TIMED_OUT,r.results().get("choose").status());assertEquals(0,calls.get());assertTrue(r.physicalExitUnconfirmed().isEmpty());
            e.register("serial",SERIAL);assertTrue(e.execute("serial",null).succeeded());
        }
    }
}
