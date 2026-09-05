package io.github.mchgood.flow;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.*;
import static io.github.mchgood.flow.Definition.*;

/** Single-process DAG engine. Each root caller coordinates its entire child execution tree. */
public final class DefaultFlowEngine implements FlowEngine {
    private static final ThreadLocal<DefaultFlowEngine> WORKER=new ThreadLocal<>();
    private static final System.Logger LOG=System.getLogger(DefaultFlowEngine.class.getName());
    private final FlowCompiler compiler;private final ConditionEvaluator evaluator;private final EngineConfig config;
    private final ThreadPoolExecutor pool;private final Semaphore permits;
    private final Object registryLock=new Object();
    private volatile Map<String,Definition> registry=Map.of();private volatile boolean closed;
    private final Set<Root> roots=ConcurrentHashMap.newKeySet();
    public DefaultFlowEngine(NodeResolver resolver,ConditionEvaluator evaluator){this(resolver,evaluator,EngineConfig.defaults());}
    public DefaultFlowEngine(NodeResolver resolver,ConditionEvaluator evaluator,EngineConfig config){
        this.evaluator=Objects.requireNonNull(evaluator);this.config=Objects.requireNonNull(config);compiler=new FlowCompiler(Objects.requireNonNull(resolver),evaluator);
        permits=new Semaphore(config.maxConcurrentExecutions());var index=new AtomicInteger();
        pool=new ThreadPoolExecutor(config.workerThreads(),config.workerThreads(),0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(config.queueCapacity()),r->{Thread t=new Thread(r,"flow-worker-"+index.incrementAndGet());t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    @Override public FlowDescriptor register(String id,String markdown){return registerAll(Map.of(id,markdown)).get(0);}
    @Override public List<FlowDescriptor> registerAll(Map<String,String> input){
        Objects.requireNonNull(input);if(input.isEmpty())return List.of();
        Map<String,Definition> candidates=new LinkedHashMap<>();input.forEach((id,text)->candidates.put(id,compiler.compile(id,text)));
        synchronized(registryLock){
            if(closed)throw new FlowException("ENGINE_CLOSED","Engine is closed");
            Map<String,Definition> next=new LinkedHashMap<>(registry);
            candidates.forEach((id,definition)->{if(next.putIfAbsent(id,definition)!=null)throw new FlowException("DUPLICATE_FLOW",id);});
            Map<String,Integer> depth=new HashMap<>();for(var id:next.keySet())referenceDepth(id,next,new LinkedHashSet<>(),depth);
            registry=Collections.unmodifiableMap(next);
        }
        return candidates.values().stream().map(Definition::descriptor).toList();
    }
    private int referenceDepth(String id,Map<String,Definition> definitions,Set<String> visiting,Map<String,Integer> memo){
        if(memo.containsKey(id))return memo.get(id);
        if(visiting.size()>config.maxSubflowDepth()+1)throw new FlowException("SUBFLOW_LIMIT_EXCEEDED","Reference depth exceeded");
        if(!visiting.add(id))throw new FlowException("FLOW_REFERENCE_CYCLE",String.join(" -> ",visiting)+" -> "+id);
        Definition d=definitions.get(id);if(d==null)throw new FlowException("SUBFLOW_NOT_FOUND",String.join(" -> ",visiting));
        int max=0;for(var n:d.nodes.values())if(n.type==Type.CALL_FLOW)max=Math.max(max,1+referenceDepth(n.target,definitions,visiting,memo));
        visiting.remove(id);if(max>config.maxSubflowDepth())throw new FlowException("SUBFLOW_LIMIT_EXCEEDED","Static depth: "+id);memo.put(id,max);return max;
    }
    @Override public FlowResult execute(String id,Object input,ExecutionOptions options){
        if(WORKER.get()==this)throw new FlowException("REENTRANT_EXECUTION","Use an explicit subflow node");
        Objects.requireNonNull(options);Root root;
        synchronized(registryLock){
            if(closed)throw new FlowException("ENGINE_CLOSED","Engine is closed");
            Definition definition=registry.get(id);if(definition==null)throw new FlowException("FLOW_NOT_FOUND",id);
            if(!permits.tryAcquire())throw new FlowException("FLOW_REJECTED","Root capacity exhausted");
            root=new Root(registry);Duration duration=options.timeout()==null?config.flowTimeout():options.timeout();
            root.main=new Execution(root,definition,input,null,null,System.nanoTime()+duration.toNanos());root.executions.add(root.main);roots.add(root);
        }
        boolean interrupted=false;root.lock.lock();
        try {
            while(root.main.result==null){
                expire(root);
                boolean changed=pump(root);
                if(root.main.result!=null)break;
                if(changed)continue;
                try {root.wakeup.awaitNanos(waitNanos(root));}
                catch(InterruptedException e){interrupted=true;forceTree(root.main,FlowStatus.FAILED,"CALLER_INTERRUPTED");settle(root);}
            }
            return root.main.result;
        } finally {root.lock.unlock();roots.remove(root);permits.release();if(interrupted)Thread.currentThread().interrupt();}
    }
    private final class Root {
        final ReentrantLock lock=new ReentrantLock();final Condition wakeup=lock.newCondition();
        final Map<String,Definition> definitions;final List<Execution> executions=new ArrayList<>();Execution main;
        int created=1;
        Root(Map<String,Definition> d){definitions=d;}
    }
    private final class Execution {
        final Root root;final Definition definition;final Object input;final Execution parent;final RuntimeNode call;
        final String id=UUID.randomUUID().toString(),path;final int depth;
        final Instant started=Instant.now();final long deadline;
        final Map<String,RuntimeNode> nodes=new LinkedHashMap<>();final Deque<RuntimeNode> ready=new ArrayDeque<>();
        final List<FlowError> errors=new ArrayList<>();boolean stopping,forced;FlowStatus forcedStatus;FlowResult result;int inFlight;
        Execution(Root root,Definition d,Object input,Execution parent,RuntimeNode call,long deadline){
            this.root=root;definition=d;this.input=input;this.parent=parent;this.call=call;this.deadline=deadline;depth=parent==null?0:parent.depth+1;
            path=parent==null?d.id:parent.path+"/"+call.spec.id+":"+d.id;
            d.ordered.forEach(n->nodes.put(n.id,new RuntimeNode(this,n)));ready.add(nodes.get("start"));
            log("flow-start",this,null,null);
        }
    }
    private final class RuntimeNode {
        final Execution execution;final Node spec;int remaining,active;NodeStatus status=NodeStatus.PENDING;
        Instant started,ended;long deadline=Long.MAX_VALUE;String skip,selected;Object value;FlowError error;
        Work work;Execution child;boolean submitted;final Set<String> resolved=new HashSet<>();
        RuntimeNode(Execution e,Node n){execution=e;spec=n;remaining=n.in.size();}
        NodeRecord record(){return new NodeRecord(spec.id,spec.target,spec.type.name(),status,status==NodeStatus.SUCCEEDED,value,skip,error,started,ended,selected);}
    }
    private boolean pump(Root r){
        boolean changed=false;
        for(var e:new ArrayList<>(r.executions)){
            if(e.result!=null||e.forced)continue;
            if(!e.stopping){
                int count=e.ready.size();
                while(count-->0&&!e.stopping){
                    var n=e.ready.remove();if(n.status!=NodeStatus.PENDING||n.submitted)continue;
                    if(n.spec.type!=Type.START){
                        if(n.active==0){skip(n,"BRANCH_NOT_SELECTED",true);changed=true;continue;}
                        int expected=n.spec.type==Type.XOR_JOIN?1:n.spec.in.size();
                        if(n.active!=expected){fail(n,NodeStatus.FAILED,n.spec.type==Type.XOR_JOIN?"GATEWAY_CONFLICT":"INPUT_PATH_MISMATCH","Active input mismatch");changed=true;continue;}
                    }
                    switch(n.spec.type){
                        case TASK,XOR_SPLIT->{
                            if(!hasSlot(e)){e.ready.add(n);continue;}
                            n.submitted=true;adjustSlots(e,1);n.work=new Work(n,context(n));
                            try{pool.execute(n.work);}catch(RejectedExecutionException ex){n.work.release();fail(n,NodeStatus.FAILED,"RESOURCE_REJECTED","Worker queue full");}
                        }
                        case CALL_FLOW->{startChild(n);}
                        default->{n.started=Instant.now();success(n,null,null);}
                    }
                    changed=true;
                }
            }
        }
        return settle(r)||changed;
    }
    private void startChild(RuntimeNode n){
        Execution p=n.execution;Root r=p.root;
        long active=r.executions.stream().filter(e->e.parent!=null&&e.result==null).count();
        if(r.created>=config.maxExecutionsPerRoot()||active>=config.maxActiveChildren()||p.depth>=config.maxSubflowDepth()){
            fail(n,NodeStatus.FAILED,"SUBFLOW_LIMIT_EXCEEDED","Child execution limit");return;
        }
        n.status=NodeStatus.RUNNING;n.started=Instant.now();n.deadline=Math.min(p.deadline,System.nanoTime()+config.flowTimeout().toNanos());
        n.child=new Execution(r,r.definitions.get(n.spec.target),p.input,p,n,n.deadline);r.created++;r.executions.add(n.child);
    }
    private NodeContext context(RuntimeNode n){
        Map<String,NodeRecord> records=new LinkedHashMap<>();
        for(String id:n.spec.ancestors){var a=n.execution.nodes.get(id);if(a.status==NodeStatus.PENDING||a.status==NodeStatus.RUNNING)throw new IllegalStateException("Unresolved ancestor "+id);records.put(id,a.record());}
        return new NodeContext(n.execution.id,n.execution.definition.id,n.spec.id,n.execution.input,records);
    }
    private boolean hasSlot(Execution e){for(;e!=null;e=e.parent)if(e.inFlight>=config.maxInFlightPerExecution())return false;return true;}
    private void adjustSlots(Execution e,int delta){for(;e!=null;e=e.parent)e.inFlight+=delta;}
    private void publish(RuntimeNode from,String selected,boolean inactive){
        for(var edge:from.spec.out){
            var target=from.execution.nodes.get(edge.to.id);
            if(!target.resolved.add(edge.id))continue;
            target.remaining--;if(!inactive&&(selected==null||selected.equals(edge.id)))target.active++;
            if(target.remaining==0)from.execution.ready.add(target);
        }
    }
    private void success(RuntimeNode n,Object value,String selected){
        if(n.execution.forced||n.execution.result!=null||terminal(n.status))return;
        n.status=NodeStatus.SUCCEEDED;n.value=value;n.selected=selected;n.ended=Instant.now();
        if(!n.execution.stopping)publish(n,selected,false);log("node-success",n.execution,n,null);
    }
    private void skip(RuntimeNode n,String reason,boolean propagate){
        if(n.status!=NodeStatus.PENDING)return;n.status=NodeStatus.SKIPPED;n.skip=reason;n.ended=Instant.now();
        if(n.work!=null)n.work.cancelWork();if(propagate)publish(n,null,true);
    }
    private void fail(RuntimeNode n,NodeStatus status,String code,String message){
        if(terminal(n.status)||n.execution.result!=null)return;
        n.status=status;n.ended=Instant.now();n.error=new FlowError(code,message,n.execution.id,n.spec.id,n.execution.path);n.execution.errors.add(n.error);
        log("node-failed",n.execution,n,code);stop(n.execution);if(n.work!=null)n.work.cancelWork();
    }
    private void stop(Execution e){e.stopping=true;for(var n:e.nodes.values())if(n.status==NodeStatus.PENDING)skip(n,"FLOW_STOPPED",false);e.ready.clear();}
    private boolean settle(Root root){
        boolean changed=false;
        for(int i=root.executions.size()-1;i>=0;i--){
            var e=root.executions.get(i);if(e.result!=null)continue;
            boolean pending=e.nodes.values().stream().anyMatch(n->n.status==NodeStatus.PENDING||n.status==NodeStatus.RUNNING);
            if(!e.forced&&(pending||e.inFlight>0))continue;
            FlowStatus status=e.forced?e.forcedStatus:(!e.errors.isEmpty()?FlowStatus.FAILED:FlowStatus.SUCCEEDED);
            if(status==FlowStatus.SUCCEEDED&&e.nodes.get("finish").status!=NodeStatus.SUCCEEDED){status=FlowStatus.FAILED;e.errors.add(new FlowError("NO_ACTIVE_PATH","Finish not reached",e.id,"finish",e.path));}
            Map<String,NodeRecord> records=new LinkedHashMap<>();e.nodes.forEach((id,n)->records.put(id,n.record()));
            List<String> unfinished=new ArrayList<>();
            for(var x:root.executions)if(descendant(x,e))for(var n:x.nodes.values())if(n.work!=null&&!n.work.exited)unfinished.add(x.path+"/"+n.spec.id+"@"+x.id);
            e.result=new FlowResult(e.id,root.main.id,e.parent==null?null:e.parent.id,e.definition.id,e.definition.hash,status,e.started,Instant.now(),records,e.errors,unfinished);
            log("flow-end",e,null,status.name());changed=true;
            if(e.call!=null&&!terminal(e.call.status)&&e.parent.result==null){
                var call=e.call;
                if(status==FlowStatus.SUCCEEDED)success(call,new ChildFlowResultView(status.name(),e.id,e.result.results()),null);
                else {
                    e.parent.errors.addAll(e.errors);
                    fail(call,status==FlowStatus.TIMED_OUT?NodeStatus.TIMED_OUT:NodeStatus.FAILED,status==FlowStatus.TIMED_OUT?"CHILD_FLOW_TIMEOUT":"CHILD_FLOW_FAILED","Child "+e.definition.id+" "+status);
                }
            }
        }
        return changed;
    }
    private boolean descendant(Execution e,Execution ancestor){for(;e!=null;e=e.parent)if(e==ancestor)return true;return false;}
    private void expire(Root r){
        long now=System.nanoTime();
        for(var e:r.executions){
            if(e.result!=null||e.forced)continue;
            if(now-e.deadline>=0){forceTree(e,FlowStatus.TIMED_OUT,"FLOW_TIMEOUT");continue;}
            for(var n:e.nodes.values())if(n.status==NodeStatus.RUNNING&&n.spec.type!=Type.CALL_FLOW&&now-n.deadline>=0)fail(n,NodeStatus.TIMED_OUT,"NODE_TIMEOUT","Node deadline exceeded");
        }
    }
    private void forceTree(Execution ancestor,FlowStatus status,String code){
        for(var e:ancestor.root.executions)if(e.result==null&&descendant(e,ancestor)){
            e.forced=true;e.forcedStatus=status;stop(e);
            for(var n:e.nodes.values())if(n.status==NodeStatus.RUNNING){
                n.status=status==FlowStatus.TIMED_OUT?NodeStatus.TIMED_OUT:NodeStatus.FAILED;n.ended=Instant.now();
                n.error=new FlowError(code,"Execution terminated",e.id,n.spec.id,e.path);e.errors.add(n.error);if(n.work!=null)n.work.cancelWork();
            }
            if(e.errors.isEmpty())e.errors.add(new FlowError(code,"Execution terminated",e.id,null,e.path));
        }
        ancestor.root.wakeup.signalAll();
    }
    private long waitNanos(Root r){
        long now=System.nanoTime(),wait=TimeUnit.SECONDS.toNanos(1);
        for(var e:r.executions)if(e.result==null&&!e.forced){wait=Math.min(wait,e.deadline-now);for(var n:e.nodes.values())if(n.status==NodeStatus.RUNNING)wait=Math.min(wait,n.deadline-now);}
        return Math.max(1,wait);
    }
    private final class Work extends FutureTask<Void> {
        final RuntimeNode node;boolean exited;
        Work(RuntimeNode n,NodeContext context){super(()->{runNode(n,context);return null;});node=n;}
        @Override public void run(){try{super.run();}finally{var root=node.execution.root;root.lock.lock();try{release();}finally{root.lock.unlock();}}}
        void release(){if(exited)return;exited=true;adjustSlots(node.execution,-1);node.execution.root.wakeup.signalAll();}
        void cancelWork(){cancel(true);if(pool.remove(this))release();}
    }
    private void runNode(RuntimeNode n,NodeContext context){
        Root root=n.execution.root;root.lock.lock();
        try{
            expire(root);if(n.execution.stopping||n.execution.forced||n.status!=NodeStatus.PENDING)return;
            n.status=NodeStatus.RUNNING;n.started=Instant.now();n.deadline=Math.min(n.execution.deadline,System.nanoTime()+(n.spec.type==Type.XOR_SPLIT?config.gatewayTimeout():config.nodeTimeout()).toNanos());root.wakeup.signalAll();
            log("node-start",n.execution,n,null);
        }finally{root.lock.unlock();}
        WORKER.set(this);
        try {
            Object value;String selected=null;
            if(n.spec.type==Type.TASK)value=n.spec.bean.execute(context);
            else {
                Edge match=null,fallback=null;int matches=0;
                for(var e:n.spec.out){
                    if(e.fallback()){fallback=e;continue;}
                    boolean yes=evaluator.evaluate(e.condition,context);
                    if(yes){matches++;match=e;}
                }
                if(matches>1)throw new FlowException("CONDITION_CONFLICT","Multiple conditions true at "+n.spec.id);
                if(match==null)match=fallback;
                if(match==null)throw new FlowException("NO_MATCHING_BRANCH",n.spec.id);
                selected=match.id;value=null;
            }
            root.lock.lock();try{expire(root);success(n,value,selected);root.wakeup.signalAll();}finally{root.lock.unlock();}
        }catch(Throwable ex){
            root.lock.lock();try{expire(root);fail(n,NodeStatus.FAILED,ex instanceof FlowException f?f.code():"NODE_FAILED",ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage());root.wakeup.signalAll();}finally{root.lock.unlock();}
            if(ex instanceof VirtualMachineError error)throw error;
        }finally{WORKER.remove();}
    }
    private static boolean terminal(NodeStatus status){return status!=NodeStatus.PENDING&&status!=NodeStatus.RUNNING;}
    private static void log(String event,Execution e,RuntimeNode n,String reason){LOG.log(System.Logger.Level.DEBUG,event+" execution="+e.id+" flow="+e.definition.id+" node="+(n==null?"":n.spec.id)+" reason="+reason);}
    @Override public void close(){
        if(WORKER.get()==this)throw new FlowException("REENTRANT_EXECUTION","close from worker is unsupported");
        synchronized(registryLock){closed=true;}
        long end=System.nanoTime()+config.closeTimeout().toNanos();boolean interrupted=false;
        while(!roots.isEmpty()&&System.nanoTime()<end){try{Thread.sleep(5);}catch(InterruptedException e){interrupted=true;break;}}
        for(var root:roots){root.lock.lock();try{forceTree(root.main,FlowStatus.FAILED,"ENGINE_CLOSED");settle(root);root.wakeup.signalAll();}finally{root.lock.unlock();}}
        for(var runnable:pool.shutdownNow())if(runnable instanceof DefaultFlowEngine.Work w){var root=w.node.execution.root;root.lock.lock();try{w.cancel(false);w.release();}finally{root.lock.unlock();}}
        if(interrupted)Thread.currentThread().interrupt();
    }
}
