package io.github.mchgood.flow;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.aop.framework.ProxyFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class FlowEngineTest {
    static String md(String body){return "# Flow\n\n```mermaid\nflowchart TD\n"+body+"\n```\n";}
    static EngineConfig config(int threads,int inFlight,Duration nodeTimeout,Duration flowTimeout){return new EngineConfig(threads,16,8,inFlight,8,64,16,nodeTimeout,nodeTimeout,flowTimeout,Duration.ofMillis(20));}
    static DefaultFlowEngine engine(Map<String,FlowNode> beans){return engine(beans,4,8);}
    static DefaultFlowEngine engine(Map<String,FlowNode> beans,int threads,int inFlight){return new DefaultFlowEngine(id->{var n=beans.get(id);if(n==null)throw new FlowException("BEAN_NOT_FOUND",id);return n;},new SpelConditionEvaluator(),config(threads,inFlight,Duration.ofSeconds(2),Duration.ofSeconds(3)));}
    @Test void aliasCallsAndAncestorResults(){
        List<String> calls=new CopyOnWriteArrayList<>();
        FlowNode check=c->{calls.add(c.nodeId());return c.nodeId();};
        FlowNode save=c->{assertEquals("check_before",c.ancestorValue("check_before",String.class));return null;};
        try(var e=engine(Map.of("check",check,"save",save))){
            e.register("order",md("start([开始]) --> check_before[\"before\"] --> save[\"save\"] --> check_after[\"after\"] --> finish([结束])"));
            var result=e.execute("order",Map.of());assertTrue(result.succeeded(),result.errors().toString());
            assertEquals(List.of("check_before","check_after"),calls);assertTrue(result.results().get("save").present());assertNull(result.results().get("save").value());
        }
    }
    @Test void parallelReallyOverlapsAndJoins(){
        var barrier=new CyclicBarrier(2);var count=new AtomicInteger();
        FlowNode work=c->{barrier.await(1,TimeUnit.SECONDS);count.incrementAndGet();return c.nodeId();};
        try(var e=engine(Map.of("a",work,"b",work,"endTask",c->{assertEquals(2,count.get());return "ok";}))){
            e.register("parallel",md("start([开始]) --> fork{\"+\"}\nfork --> a[\"a\"]\nfork --> b[\"b\"]\na --> join{\"+\"}\nb --> join\njoin --> endTask[\"end\"] --> finish([结束])"));
            assertTrue(e.execute("parallel",null).succeeded());
        }
    }
    @Test void dependencyReadyDoesNotWaitForUnrelatedBranch(){
        var enrichDone=new CountDownLatch(1);
        try(var e=engine(Map.of("slow",c->{assertTrue(enrichDone.await(1,TimeUnit.SECONDS));return 1;},"fast",c->1,"enrich",c->{enrichDone.countDown();return 2;}))){
            e.register("dag",md("start([开始]) --> slow[\"slow\"]\nstart --> fast[\"fast\"] --> enrich[\"enrich\"]\nslow --> finish([结束])\nenrich --> finish"));assertTrue(e.execute("dag",null).succeeded());
        }
    }
    static String conditional(String first,String second){return md("start([开始]) --> gate{\"choose\"}\ngate -->|\""+first+"\"| yes[\"yes\"]\ngate -->|\""+second+"\"| no[\"no\"]\nyes --> merge{\"X\"}\nno --> merge\nmerge --> finish([结束])");}
    @Test void spelAndDefaultBranches(){
        try(var e=engine(Map.of("yes",c->"yes","no",c->"no"))){
            e.register("choice",conditional("#input.amount <= 1000","default"));
            var a=e.execute("choice",Map.of("amount",10));assertTrue(a.succeeded(),a.errors().toString());
            assertEquals(NodeStatus.SUCCEEDED,a.results().get("yes").status());assertEquals("BRANCH_NOT_SELECTED",a.results().get("no").skipReason());
            var b=e.execute("choice",Map.of("amount",2000));assertTrue(b.succeeded());assertEquals(NodeStatus.SUCCEEDED,b.results().get("no").status());
        }
    }
    @Test void conditionsReadCompletedAncestor(){
        try(var e=engine(Map.of("check",c->Map.of("passed",true),"yes",c->1,"no",c->2))){
            String graph=conditional("#results['check'].present and #results['check'].value.passed","default").replace("start([开始]) --> gate","start([开始]) --> check[\"check\"] --> gate");
            e.register("choice",graph);var r=e.execute("choice",null);assertTrue(r.succeeded(),r.errors().toString());assertEquals(NodeStatus.SUCCEEDED,r.results().get("yes").status());
        }
    }
    @Test void conflictingConditionsNeverRunTasks(){
        var calls=new AtomicInteger();try(var e=engine(Map.of("yes",c->calls.incrementAndGet(),"no",c->calls.incrementAndGet()))){
            e.register("choice",conditional("true","true"));var r=e.execute("choice",null);assertFalse(r.succeeded());assertEquals("CONDITION_CONFLICT",r.errors().get(0).code());assertEquals(0,calls.get());
        }
    }
    @Test void allFalseWithoutDefaultFails(){try(var e=engine(Map.of("yes",c->1,"no",c->2))){e.register("choice",conditional("false","false"));assertEquals("NO_MATCHING_BRANCH",e.execute("choice",null).errors().get(0).code());}}
    @ParameterizedTest @ValueSource(strings={"null","1","'true'"}) void rejectsNonBoolean(String expr){try(var e=engine(Map.of("yes",c->1,"no",c->2))){e.register("choice",conditional(expr,"default"));assertEquals("EXPRESSION_TYPE_ERROR",e.execute("choice",null).errors().get(0).code());}}
    @Test void exceptionDoesNotUseDefault(){try(var e=engine(Map.of("yes",c->1,"no",c->2))){e.register("choice",conditional("#input.missing.foo > 0","default"));assertFalse(e.execute("choice",Map.of()).succeeded());}}
    @ParameterizedTest @ValueSource(strings={"T(java.lang.System).exit(0)","@service.call()","new java.lang.String('x')","#input.clear()","#input['amount'] = 7","#input.amount++","#input.class","#root","#results[#input.key].present"})
    void rejectsForbiddenSpel(String expr){try(var e=engine(Map.of("yes",c->1,"no",c->2))){assertThrows(FlowException.class,()->e.register("choice",conditional(expr,"default")));}}
    @Test void unknownAncestorRejectedAtEvaluation(){try(var e=engine(Map.of("yes",c->1,"no",c->2))){e.register("choice",conditional("#results['no'].present","default"));assertFalse(e.execute("choice",null).succeeded());}}
    @Test void duplicateDefaultsFail(){try(var e=engine(Map.of("yes",c->1,"no",c->2))){assertEquals("MULTIPLE_DEFAULTS",assertThrows(FlowException.class,()->e.register("choice",conditional("default","default"))).code());}}
    @ParameterizedTest @ValueSource(strings={"a_","a__before","_before"}) void invalidAlias(String id){try(var e=engine(Map.of("a",c->1))){assertThrows(FlowException.class,()->e.register("flow",md("start([开始]) --> "+id+"[\"a\"] --> finish([结束])")));}}
    @Test void cyclesAndDuplicateEdgesFail(){try(var e=engine(Map.of("a",c->1,"b",c->1))){assertThrows(FlowException.class,()->e.register("flow",md("start([开始]) --> a --> b --> a\nb --> finish([结束])")));assertThrows(FlowException.class,()->e.register("flow",md("start([开始]) --> a --> finish([结束])\nstart --> a")));}}
    @Test void labelsContainingArrowAreNotSplit(){try(var e=engine(Map.of("a",c->1))){e.register("flow",md("start([开始]) --> a[\"a --> value\"] --> finish([结束])"));assertTrue(e.execute("flow",null).succeeded());}}
    @Test void malformedLabelHasLocation(){try(var e=engine(Map.of("a",c->1))){var x=assertThrows(FlowException.class,()->e.register("flow",md("start([开始]) --> a[\"unfinished")));assertTrue(x.getMessage().contains("flow:5:"));}}
    @Test void markdownDefinitionContractIsStrict(){try(var e=engine(Map.of("a",c->1))){
        assertEquals("MERMAID_BLOCK_COUNT",assertThrows(FlowException.class,()->e.register("flow","# no graph")).code());
        assertEquals("MERMAID_BLOCK_COUNT",assertThrows(FlowException.class,()->e.register("flow",md("start([开始]) --> a --> finish([结束])")+md("start([开始]) --> a --> finish([结束])"))).code());
        assertEquals("INVALID_HEADER",assertThrows(FlowException.class,()->e.register("flow","```mermaid\ngraph TD\nstart([开始]) --> a --> finish([结束])\n```" )).code());
    }}
    @Test void endpointAndReachabilityValidation(){try(var e=engine(Map.of("a",c->1,"b",c->1))){
        assertEquals("INVALID_ENDPOINTS",assertThrows(FlowException.class,()->e.register("flow",md("a --> finish([结束])"))).code());
        assertEquals("INVALID_ENDPOINTS",assertThrows(FlowException.class,()->e.register("flow",md("start([开始]) --> a"))).code());
        assertEquals("UNREACHABLE_NODE",assertThrows(FlowException.class,()->e.register("flow",md("start([开始]) --> a --> finish([结束])\nb --> finish"))).code());
    }}
    @Test void gatewayShapeAndDegreeValidation(){try(var e=engine(Map.of("a",c->1,"b",c->1))){
        assertEquals("GATEWAY_DEGREE",assertThrows(FlowException.class,()->e.register("flow",md("start([开始]) --> gate{\"+\"} --> a --> finish([结束])"))).code());
        assertEquals("MISSING_CONDITION",assertThrows(FlowException.class,()->e.register("flow",md("start([开始]) --> gate{\"choose\"}\ngate --> a\ngate -->|\"default\"| b\na --> join{\"X\"}\nb --> join\njoin --> finish([结束])"))).code());
        assertEquals("CONDITION_NOT_ALLOWED",assertThrows(FlowException.class,()->e.register("flow",md("start([开始]) --> a\na -->|\"true\"| finish([结束])"))).code());
    }}
    @Test void duplicateFlowRegistrationIsAtomic(){try(var e=engine(Map.of("a",c->1))){
        e.register("one",md("start([开始]) --> a --> finish([结束])"));
        assertEquals("DUPLICATE_FLOW",assertThrows(FlowException.class,()->e.registerAll(Map.of("one",md("start([开始]) --> a --> finish([结束])"),"two",md("start([开始]) --> a --> finish([结束])")))).code());
        assertEquals("FLOW_NOT_FOUND",assertThrows(FlowException.class,()->e.execute("two",null)).code());
        assertTrue(e.execute("one",null).succeeded());
    }}
    @Test void closedEngineRejectsRegistrationAndExecution(){var e=engine(Map.of("a",c->1));e.register("flow",md("start([开始]) --> a --> finish([结束])"));e.close();
        assertEquals("ENGINE_CLOSED",assertThrows(FlowException.class,()->e.register("next",md("start([开始]) --> a --> finish([结束])"))).code());
        assertEquals("ENGINE_CLOSED",assertThrows(FlowException.class,()->e.execute("flow",null)).code());
    }
    @Test void singletonProxyIsPreserved(){
        try(var context=new GenericApplicationContext()){
            var invoked=new AtomicInteger();ProxyFactory pf=new ProxyFactory((FlowNode)c->"ok");pf.addAdvice((org.aopalliance.intercept.MethodInterceptor)inv->{invoked.incrementAndGet();return inv.proceed();});
            context.getBeanFactory().registerSingleton("work",pf.getProxy());context.refresh();
            try(var e=new DefaultFlowEngine(new SpringNodeResolver(context.getBeanFactory()),new SpelConditionEvaluator())){e.register("flow",md("start([开始]) --> work --> finish([结束])"));assertTrue(e.execute("flow",null).succeeded());assertEquals(1,invoked.get());}
        }
    }
    @Test void subflowsCompleteWithOneWorkerAndOneSlot(){
        try(var e=engine(Map.of("work",c->{assertEquals("payload",c.input(String.class));return c.executionId();}),1,1)){
            e.registerAll(Map.of("root",md("start([开始]) --> child_before[[\"child\"]] --> child_after[[\"child\"]] --> finish([结束])"),"child",md("start([开始]) --> leaf[[\"leaf\"]] --> finish([结束])"),"leaf",md("start([开始]) --> work --> finish([结束])")));
            var r=e.execute("root","payload");assertTrue(r.succeeded(),r.errors().toString());
            var a=(ChildFlowResultView)r.results().get("child_before").value();var b=(ChildFlowResultView)r.results().get("child_after").value();assertNotEquals(a.executionId(),b.executionId());assertFalse(r.results().containsKey("work"));
        }
    }
    @Test void childCannotReadParentResults(){
        try(var e=engine(Map.of("parentTask",c->1,"childTask",c->{assertThrows(FlowException.class,()->c.ancestorOutput("parentTask"));return 2;}))){
            e.registerAll(Map.of("root",md("start([开始]) --> parentTask --> child[[\"child\"]] --> finish([结束])"),"child",md("start([开始]) --> childTask --> finish([结束])")));assertTrue(e.execute("root",null).succeeded());
        }
    }
    @Test void childFailurePropagates(){try(var e=engine(Map.of("work",c->{throw new IllegalStateException("broken");}))){e.registerAll(Map.of("root",md("start([开始]) --> child[[\"child\"]] --> finish([结束])"),"child",md("start([开始]) --> work --> finish([结束])")));var r=e.execute("root",null);assertEquals(NodeStatus.FAILED,r.results().get("child").status());assertTrue(r.errors().stream().anyMatch(x->x.code().equals("CHILD_FLOW_FAILED")));}}
    @Test void childReferencesCheckedAtomically(){try(var e=engine(Map.of("work",c->1))){
        assertThrows(FlowException.class,()->e.registerAll(Map.of("a",md("start([开始]) --> b[[\"b\"]] --> finish([结束])"),"b",md("start([开始]) --> a[[\"a\"]] --> finish([结束])"))));
        assertEquals("FLOW_NOT_FOUND",assertThrows(FlowException.class,()->e.execute("a",null)).code());
        assertEquals("SUBFLOW_NOT_FOUND",assertThrows(FlowException.class,()->e.register("a",md("start([开始]) --> missing[[\"missing\"]] --> finish([结束])"))).code());
    }}
    @Test void failureSkipsQueuedWork(){var count=new AtomicInteger();try(var e=engine(Map.of("a",c->{throw new Exception("bad");},"b",c->count.incrementAndGet()),1,8)){
        e.register("flow",md("start([开始]) --> a\nstart --> b\na --> finish([结束])\nb --> finish"));var r=e.execute("flow",null);assertFalse(r.succeeded());assertEquals(0,count.get());
    }}
    @Test void concurrentExecutionsAreIsolated() throws Exception {
        try(var e=engine(Map.of("work",c->c.input(Integer.class)))){
            e.register("flow",md("start([开始]) --> work --> finish([结束])"));var callers=Executors.newFixedThreadPool(4);
            try{var fs=new ArrayList<Future<FlowResult>>();for(int i=0;i<4;i++){int value=i;fs.add(callers.submit(()->e.execute("flow",value)));}for(int i=0;i<4;i++)assertEquals(i,fs.get(i).get().results().get("work").value());}finally{callers.shutdownNow();}
        }
    }
    @Test void lateResultCannotOverwriteTimeout() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var exited=new CountDownLatch(1);
        FlowNode stubborn=c->{started.countDown();try{while(true){try{release.await();break;}catch(InterruptedException ignored){}}return "late";}finally{exited.countDown();}};
        var cfg=config(1,1,Duration.ofMillis(40),Duration.ofMillis(100));
        try(var e=new DefaultFlowEngine(id->stubborn,new SpelConditionEvaluator(),cfg)){
            e.register("flow",md("start([开始]) --> work --> finish([结束])"));var callers=Executors.newSingleThreadExecutor();
            try{var future=callers.submit(()->e.execute("flow",null));assertTrue(started.await(1,TimeUnit.SECONDS));var r=future.get(1,TimeUnit.SECONDS);assertEquals(FlowStatus.TIMED_OUT,r.status());assertFalse(r.physicalExitUnconfirmed().isEmpty());release.countDown();assertTrue(exited.await(1,TimeUnit.SECONDS));assertNull(r.results().get("work").value());assertEquals(NodeStatus.TIMED_OUT,r.results().get("work").status());}finally{release.countDown();callers.shutdownNow();}
        }
    }
    @Test void reentrantExecuteFailsInsteadOfDeadlock(){var ref=new AtomicReference<DefaultFlowEngine>();try(var e=engine(Map.of("work",c->ref.get().execute("flow",null)),1,1)){ref.set(e);e.register("flow",md("start([开始]) --> work --> finish([结束])"));assertEquals("REENTRANT_EXECUTION",e.execute("flow",null).errors().get(0).code());}}
    @Test void noActiveBranchStartsNestedSubflow(){var calls=new AtomicInteger();try(var e=engine(Map.of("yes",c->1,"work",c->calls.incrementAndGet()))){
        String parent=conditional("true","default").replace("no[\"no\"]","child[[\"child\"]]").replace("\nno --> merge","\nchild --> merge");
        e.registerAll(Map.of("root",parent,"child",md("start([开始]) --> work --> finish([结束])")));assertTrue(e.execute("root",null).succeeded());assertEquals(0,calls.get());
    }}

    @Test void siblingResultsCannotLeakEvenIfCompleted(){
        var done=new CountDownLatch(1);
        try(var e=engine(Map.of("a",c->{done.countDown();return 1;},"b",c->{assertTrue(done.await(1,TimeUnit.SECONDS));assertThrows(FlowException.class,()->c.ancestorOutput("a"));return 2;}))){
            e.register("flow",md("start([开始]) --> a\nstart --> b\na --> finish([结束])\nb --> finish"));assertTrue(e.execute("flow",null).succeeded());
        }
    }
    @Test void skippedAncestorAndSuccessfulNullDiffer(){
        FlowNode after=c->{assertFalse(c.ancestorOutput("no").present());assertTrue(c.ancestorOutput("yes").present());assertNull(c.ancestorValue("yes",Object.class));return 1;};
        try(var e=engine(Map.of("yes",c->null,"no",c->2,"after",after))){e.register("choice",conditional("true","default").replace("merge --> finish", "merge --> after --> finish"));assertTrue(e.execute("choice",null).succeeded());}
    }
    @Test void nestedInactiveBranchDoesNotBlock(){
        var calls=new AtomicInteger();
        try(var e=engine(Map.of("yes",c->1,"a",c->calls.incrementAndGet(),"b",c->calls.incrementAndGet()))){
            String body="start([开始]) --> outer{\"outer\"}\nouter -->|\"true\"| yes\nouter -->|\"default\"| inner{\"inner\"}\ninner -->|\"true\"| a\ninner -->|\"default\"| b\na --> innerJoin{\"X\"}\nb --> innerJoin\ninnerJoin --> outerJoin{\"X\"}\nyes --> outerJoin\nouterJoin --> finish([结束])";
            e.register("flow",md(body));assertTrue(e.execute("flow",null).succeeded());assertEquals(0,calls.get());
        }
    }
    @Test void prototypeBeansRejected(){try(var ctx=new GenericApplicationContext()){
        ctx.registerBean("work",FlowNode.class,()->c->1,bd->bd.setScope("prototype"));ctx.refresh();
        try(var e=new DefaultFlowEngine(new SpringNodeResolver(ctx.getBeanFactory()),new SpelConditionEvaluator())){assertEquals("BEAN_SCOPE_UNSUPPORTED",assertThrows(FlowException.class,()->e.register("flow",md("start([开始]) --> work --> finish([结束])"))).code());}
    }}
    @Test void expressionErrorIsNotHiddenByOtherTrueCondition(){try(var e=engine(Map.of("yes",c->1,"no",c->2))){e.register("flow",conditional("true","#input.missing.invalid"));assertFalse(e.execute("flow",Map.of()).succeeded());}}
    @Test void parentDeadlineCancelsChild(){
        var started=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        try(var e=engine(Map.of("work",c->{started.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException x){interrupted.countDown();throw x;}return 1;}))){
            e.registerAll(Map.of("root",md("start([开始]) --> child[[\"child\"]] --> finish([结束])"),"child",md("start([开始]) --> work --> finish([结束])")));
            var result=e.execute("root",null,ExecutionOptions.withTimeout(Duration.ofMillis(150)));assertEquals(FlowStatus.TIMED_OUT,result.status());assertTrue(interrupted.await(1,TimeUnit.SECONDS));assertEquals(NodeStatus.TIMED_OUT,result.results().get("child").status());
        }catch(InterruptedException ex){throw new AssertionError(ex);}
    }
    @Test void rootAdmissionRejectsWithoutWaiting() throws Exception {
        var start=new CountDownLatch(1);var release=new CountDownLatch(1);
        var cfg=new EngineConfig(1,1,1,1,8,64,16,Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ofSeconds(3),Duration.ofMillis(20));
        try(var e=new DefaultFlowEngine(id->c->{start.countDown();release.await();return 1;},new SpelConditionEvaluator(),cfg)){
            e.register("flow",md("start([开始]) --> work --> finish([结束])"));var callers=Executors.newSingleThreadExecutor();try{
                var first=callers.submit(()->e.execute("flow",null));assertTrue(start.await(1,TimeUnit.SECONDS));assertEquals("FLOW_REJECTED",assertThrows(FlowException.class,()->e.execute("flow",null)).code());release.countDown();assertTrue(first.get().succeeded());
            }finally{release.countDown();callers.shutdownNow();}
        }
    }
    @Test void interruptedCallerReturnsFixedFailure() throws Exception {
        var start=new CountDownLatch(1);var result=new AtomicReference<FlowResult>();var flag=new AtomicBoolean();
        try(var e=engine(Map.of("work",c->{start.countDown();new CountDownLatch(1).await();return 1;}))){
            e.register("flow",md("start([开始]) --> work --> finish([结束])"));Thread caller=new Thread(()->{result.set(e.execute("flow",null));flag.set(Thread.currentThread().isInterrupted());});caller.start();assertTrue(start.await(1,TimeUnit.SECONDS));caller.interrupt();caller.join(1000);assertFalse(caller.isAlive());assertEquals(FlowStatus.FAILED,result.get().status());assertTrue(flag.get());
        }
    }
    @Test void closeTerminatesActiveWorkAndRejectsNewCalls() throws Exception {
        var start=new CountDownLatch(1);var e=engine(Map.of("work",c->{start.countDown();new CountDownLatch(1).await();return 1;}));
        e.register("flow",md("start([开始]) --> work --> finish([结束])"));var callers=Executors.newSingleThreadExecutor();try{
            var future=callers.submit(()->e.execute("flow",null));assertTrue(start.await(1,TimeUnit.SECONDS));e.close();assertEquals(FlowStatus.FAILED,future.get(1,TimeUnit.SECONDS).status());assertEquals("ENGINE_CLOSED",assertThrows(FlowException.class,()->e.execute("flow",null)).code());
        }finally{e.close();callers.shutdownNow();}
    }
}
