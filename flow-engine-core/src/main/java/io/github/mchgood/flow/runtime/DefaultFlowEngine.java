package io.github.mchgood.flow.runtime;

import io.github.mchgood.flow.api.ExecutionOptions;
import io.github.mchgood.flow.api.FlowDescriptor;
import io.github.mchgood.flow.api.FlowEngine;
import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.result.ChildFlowResultView;
import io.github.mchgood.flow.result.FlowError;
import io.github.mchgood.flow.result.FlowResult;
import io.github.mchgood.flow.result.FlowStatus;
import io.github.mchgood.flow.result.NodeRecord;
import io.github.mchgood.flow.result.NodeStatus;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.NodeResolver;


import io.github.mchgood.flow.internal.graph.Definition;
import io.github.mchgood.flow.internal.compiler.FlowCompiler;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.*;
import static io.github.mchgood.flow.internal.graph.Definition.*;

/**
 * 单进程、依赖就绪驱动的流程执行器，也是宿主可直接构造的标准实现。
 * <p>每个根调用由调用线程协调其整棵子执行树，业务任务和条件求值交给共享有界线程池。
 * 注册使用独立锁和不可变快照；每棵执行树的状态由 Root.lock 串行保护，不持锁调用业务代码。
 * 子流程与父流程共享协调器，不占用工作线程等待子流程，因此单工作线程也能执行嵌套流程。
 * <p>逻辑结果发布后拒绝迟到写入；工作额度直到任务物理退出或成功移出队列才释放。
 * 输入和业务输出不深拷贝；节点及扩展实现必须满足并发使用约定。实例应由宿主生命周期统一关闭。
 */
public final class DefaultFlowEngine implements FlowEngine {
    private static final ThreadLocal<DefaultFlowEngine> WORKER=new ThreadLocal<>();
    private static final System.Logger LOG=System.getLogger(DefaultFlowEngine.class.getName());
    private final FlowCompiler compiler;private final ConditionEvaluator evaluator;private final EngineConfig config;
    private final ThreadPoolExecutor pool;private final Semaphore permits;
    private final Object registryLock=new Object();
    private volatile Map<String,Definition> registry=Map.of();private volatile boolean closed;
    private final Set<Root> roots=ConcurrentHashMap.newKeySet();

    /**
     * 创建使用默认资源配置的引擎。
     *
     * @param resolver 注册时使用的线程安全节点解析器
     * @param evaluator 线程安全条件编译与求值器
     * @throws NullPointerException 任一依赖为 null
     */
    public DefaultFlowEngine(NodeResolver resolver,ConditionEvaluator evaluator){this(resolver,evaluator,EngineConfig.defaults());}

    /**
     * 创建拥有独立工作线程池和根调用准入额度的引擎。
     *
     * @param resolver 注册期节点绑定扩展
     * @param evaluator 注册期编译及执行期求值扩展
     * @param config 固定资源与期限配置
     * @throws NullPointerException 任一参数为 null
     */
    public DefaultFlowEngine(NodeResolver resolver,ConditionEvaluator evaluator,EngineConfig config){
        this.evaluator=Objects.requireNonNull(evaluator);this.config=Objects.requireNonNull(config);compiler=new FlowCompiler(Objects.requireNonNull(resolver),evaluator);
        permits=new Semaphore(config.maxConcurrentExecutions());var index=new AtomicInteger();
        pool=new ThreadPoolExecutor(config.workerThreads(),config.workerThreads(),0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(config.queueCapacity()),r->{Thread t=new Thread(r,"flow-worker-"+index.incrementAndGet());t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * {@inheritDoc}
     */
    @Override public FlowDescriptor register(String id,String markdown){return registerAll(Map.of(id,markdown)).get(0);}

    /**
     * {@inheritDoc}
     */
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

    /**
     * 以当前 DFS 路径识别引用环，以 memo 复用已校验子图深度。
     * 所有候选发布前一起检查，保证不会让缺失引用或过深调用进入运行阶段。
     */
    private int referenceDepth(String id,Map<String,Definition> definitions,Set<String> visiting,Map<String,Integer> memo){
        if(memo.containsKey(id))return memo.get(id);
        if(visiting.size()>config.maxSubflowDepth()+1)throw new FlowException("SUBFLOW_LIMIT_EXCEEDED","Reference depth exceeded");
        if(!visiting.add(id))throw new FlowException("FLOW_REFERENCE_CYCLE",String.join(" -> ",visiting)+" -> "+id);
        Definition d=definitions.get(id);if(d==null)throw new FlowException("SUBFLOW_NOT_FOUND",String.join(" -> ",visiting));
        int max=0;for(var n:d.nodes.values())if(n.type==Type.CALL_FLOW)max=Math.max(max,1+referenceDepth(n.target,definitions,visiting,memo));
        visiting.remove(id);if(max>config.maxSubflowDepth())throw new FlowException("SUBFLOW_LIMIT_EXCEEDED","Static depth: "+id);memo.put(id,max);return max;
    }

    /**
     * {@inheritDoc}
     */
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

    /**
     * 一棵根执行树的协调状态。
     * <p>definitions 固定本次注册快照；executions、created、main 及所属节点状态由 lock 保护。
     * wakeup 用于完成通知及期限变化，工作线程释放锁后由调用线程继续推进。
     */
    private final class Root {
        final ReentrantLock lock=new ReentrantLock();final Condition wakeup=lock.newCondition();
        final Map<String,Definition> definitions;final List<Execution> executions=new ArrayList<>();Execution main;
        int created=1;
        Root(Map<String,Definition> d){definitions=d;}
    }

    /**
     * 单个根或子流程的隔离执行实例。
     * <p>节点结果和错误独立于父实例；输入仍是同一个对象引用。
     * inFlight 汇总当前实例及其后代的物理在途任务，所有可变字段由所属根锁保护。
     */
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

    /**
     * 某次执行中的节点状态，spec 指向跨执行共享的不可变拓扑。
     * <p>remaining 统计尚未确定的入边，active 统计已激活入边，resolved 防止重复传播。
     * submitted 与 RUNNING 分开，避免排队等待被误算为任务执行时间。
     */
    private final class RuntimeNode {
        final Execution execution;final Node spec;int remaining,active;NodeStatus status=NodeStatus.PENDING;
        Instant started,ended;long deadline=Long.MAX_VALUE;String skip,selected;Object value;FlowError error;
        Work work;Execution child;boolean submitted;final Set<String> resolved=new HashSet<>();
        RuntimeNode(Execution e,Node n){execution=e;spec=n;remaining=n.in.size();}
        NodeRecord record(){return new NodeRecord(spec.id,spec.target,spec.type.name(),status,status==NodeStatus.SUCCEEDED,value,skip,error,started,ended,selected);}
    }

    /**
     * 在持有根锁时推进就绪节点和完成通知，返回是否发生进展。
     * <p>不按图层等待；当前没有额度的节点放回就绪队列，其他可执行节点仍可继续。
     * 遍历执行列表快照，以容纳本轮新创建的子流程。
     */
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

    /**
     * 持根锁创建隔离子执行，并将调用节点置为 RUNNING。
     * 子期限取父剩余期限与默认流程期限的较小值；此处只建实例，不阻塞工作线程。
     */
    private void startChild(RuntimeNode n){
        Execution p=n.execution;Root r=p.root;
        long active=r.executions.stream().filter(e->e.parent!=null&&e.result==null).count();
        if(r.created>=config.maxExecutionsPerRoot()||active>=config.maxActiveChildren()||p.depth>=config.maxSubflowDepth()){
            fail(n,NodeStatus.FAILED,"SUBFLOW_LIMIT_EXCEEDED","Child execution limit");return;
        }
        n.status=NodeStatus.RUNNING;n.started=Instant.now();n.deadline=Math.min(p.deadline,System.nanoTime()+config.flowTimeout().toNanos());
        n.child=new Execution(r,r.definitions.get(n.spec.target),p.input,p,n,n.deadline);r.created++;r.executions.add(n.child);
    }

    /**
     * 持根锁生成静态祖先快照；未终结祖先意味着调度不变量被破坏。
     */
    private NodeContext context(RuntimeNode n){
        Map<String,NodeRecord> records=new LinkedHashMap<>();
        for(String id:n.spec.ancestors){var a=n.execution.nodes.get(id);if(a.status==NodeStatus.PENDING||a.status==NodeStatus.RUNNING)throw new IllegalStateException("Unresolved ancestor "+id);records.put(id,a.record());}
        return new NodeContext(n.execution.id,n.execution.definition.id,n.spec.id,n.execution.input,records);
    }

    /**
     * 持根锁检查当前实例及所有祖先额度，防止嵌套流程绕过父级限额。
     */
    private boolean hasSlot(Execution e){for(;e!=null;e=e.parent)if(e.inFlight>=config.maxInFlightPerExecution())return false;return true;}

    /**
     * 持根锁沿父链同时记账，delta 为提交时的 +1 或物理释放时的 -1。
     */
    private void adjustSlots(Execution e,int delta){for(;e!=null;e=e.parent)e.inFlight+=delta;}

    /**
     * 持根锁传播一次边状态：未选分支也必须传播“不激活”。
     * <p>只有所有入边状态已知才进入 ready；这样汇合不会永久等待未选择的分支。
     * resolved 保证同一条边最多减少一次 remaining。
     */
    private void publish(RuntimeNode from,String selected,boolean inactive){
        for(var edge:from.spec.out){
            var target=from.execution.nodes.get(edge.to.id);
            if(!target.resolved.add(edge.id))continue;
            target.remaining--;if(!inactive&&(selected==null||selected.equals(edge.id)))target.active++;
            if(target.remaining==0)from.execution.ready.add(target);
        }
    }

    /**
     * 持根锁接受成功结果；若节点已终态或所属执行已强制终结则丢弃迟到结果。
     */
    private void success(RuntimeNode n,Object value,String selected){
        if(n.execution.forced||n.execution.result!=null||terminal(n.status))return;
        n.status=NodeStatus.SUCCEEDED;n.value=value;n.selected=selected;n.ended=Instant.now();
        if(!n.execution.stopping)publish(n,selected,false);log("node-success",n.execution,n,null);
    }

    /**
     * 持根锁跳过尚未运行的节点；正常未选分支传播不激活，停止流程时不继续传播。
     */
    private void skip(RuntimeNode n,String reason,boolean propagate){
        if(n.status!=NodeStatus.PENDING)return;n.status=NodeStatus.SKIPPED;n.skip=reason;n.ended=Instant.now();
        if(n.work!=null)n.work.cancelWork();if(propagate)publish(n,null,true);
    }

    /**
     * 持根锁写入单次失败、停止该执行的新任务并请求取消失败任务。
     * 已在运行的其他任务仍可能完成，普通失败不等于整棵树立即物理退出。
     */
    private void fail(RuntimeNode n,NodeStatus status,String code,String message){
        if(terminal(n.status)||n.execution.result!=null)return;
        n.status=status;n.ended=Instant.now();n.error=new FlowError(code,message,n.execution.id,n.spec.id,n.execution.path);n.execution.errors.add(n.error);
        log("node-failed",n.execution,n,code);stop(n.execution);if(n.work!=null)n.work.cancelWork();
    }

    /**
     * 持根锁停止新调度，跳过所有待运行节点；不把已运行任务伪装为已退出。
     */
    private void stop(Execution e){e.stopping=true;for(var n:e.nodes.values())if(n.status==NodeStatus.PENDING)skip(n,"FLOW_STOPPED",false);e.ready.clear();}

    /**
     * 持根锁按子到父顺序收尾，并把子结果投递给父调用节点。
     * <p>正常收尾等待物理在途任务归零；强制收尾允许提前返回，同时记录未确认退出任务。
     * 结果创建一次后不再被后续线程修改。
     */
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

    /**
     * 判断执行是否位于 ancestor 子树中，包含 ancestor 本身。
     */
    private boolean descendant(Execution e,Execution ancestor){for(;e!=null;e=e.parent)if(e==ancestor)return true;return false;}

    /**
     * 持根锁用单调时钟检测期限，避免墙上时钟调整影响超时。
     * 流程超时强制终结子树；节点超时走节点失败路径。
     */
    private void expire(Root r){
        long now=System.nanoTime();
        for(var e:r.executions){
            if(e.result!=null||e.forced)continue;
            if(now-e.deadline>=0){forceTree(e,FlowStatus.TIMED_OUT,"FLOW_TIMEOUT");continue;}
            for(var n:e.nodes.values())if(n.status==NodeStatus.RUNNING&&n.spec.type!=Type.CALL_FLOW&&now-n.deadline>=0)fail(n,NodeStatus.TIMED_OUT,"NODE_TIMEOUT","Node deadline exceeded");
        }
    }

    /**
     * 持根锁强制终结指定子树的逻辑状态，请求中断但不提前释放物理额度。
     */
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

    /**
     * 持根锁计算最近期限的等待量，最多一秒并至少一纳秒，避免忙等或漏过期限。
     */
    private long waitNanos(Root r){
        long now=System.nanoTime(),wait=TimeUnit.SECONDS.toNanos(1);
        for(var e:r.executions)if(e.result==null&&!e.forced){wait=Math.min(wait,e.deadline-now);for(var n:e.nodes.values())if(n.status==NodeStatus.RUNNING)wait=Math.min(wait,n.deadline-now);}
        return Math.max(1,wait);
    }

    /**
     * 任务物理生命周期包装器，区分 Future 取消与业务代码真正退出。
     * <p>FutureTask.cancel 可先于用户代码退出完成，因此不在 done 回调释放额度。
     * run 的外层 finally 统一释放；成功移出队列时可直接释放，exited 保证幂等。
     */
    private final class Work extends FutureTask<Void> {
        final RuntimeNode node;boolean exited;
        Work(RuntimeNode n,NodeContext context){super(()->{runNode(n,context);return null;});node=n;}
        @Override public void run(){try{super.run();}finally{var root=node.execution.root;root.lock.lock();try{release();}finally{root.lock.unlock();}}}

        /**
         * 持根锁幂等释放当前任务沿祖先链的额度，并唤醒协调线程。
         */
        void release(){if(exited)return;exited=true;adjustSlots(node.execution,-1);node.execution.root.wakeup.signalAll();}

        /**
         * 持根锁请求中断；只有确认从工作队列移除时才能立即释放额度。
         */
        void cancelWork(){cancel(true);if(pool.remove(this))release();}
    }

    /**
     * 工作线程的执行入口；先持锁确认准入，再解锁执行 Bean 或整组条件。
     * <p>返回和异常都重新持锁先检查期限，防止迟到成功覆盖超时；所有条件都求值，
     * 多条为真时失败，不依赖 Mermaid 边顺序。ThreadLocal 阻止当前引擎的同步重入。
     */
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

    /**
     * 判断逻辑终态；不能据此推断对应工作线程已退出。
     */
    private static boolean terminal(NodeStatus status){return status!=NodeStatus.PENDING&&status!=NodeStatus.RUNNING;}

    /**
     * 记录执行标识和状态原因，不输出业务输入或结果内容。
     */
    private static void log(String event,Execution e,RuntimeNode n,String reason){LOG.log(System.Logger.Level.DEBUG,event+" execution="+e.id+" flow="+e.definition.id+" node="+(n==null?"":n.spec.id)+" reason="+reason);}

    /**
     * {@inheritDoc}
     * <p>等待根调用使用 closeTimeout；之后 shutdownNow 仅请求中断，并不 await 工作线程退出。
     * 重复关闭不会重新开放引擎。
     */
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
