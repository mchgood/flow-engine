package io.github.mchgood.flow.runtime;

import io.github.mchgood.flow.api.ExecutionOptions;
import io.github.mchgood.flow.api.FlowDescriptor;
import io.github.mchgood.flow.api.FlowEngine;
import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.internal.compiler.FlowCompiler;
import io.github.mchgood.flow.internal.graph.Definition;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.result.ChildFlowResultView;
import io.github.mchgood.flow.result.FlowError;
import io.github.mchgood.flow.result.FlowResult;
import io.github.mchgood.flow.result.FlowStatus;
import io.github.mchgood.flow.result.NodeRecord;
import io.github.mchgood.flow.result.NodeStatus;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.FlowExecutionInterceptor;
import io.github.mchgood.flow.spi.NodeExecutionInterceptor;
import io.github.mchgood.flow.spi.NodeOutcome;
import io.github.mchgood.flow.spi.NodeResolver;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static io.github.mchgood.flow.internal.graph.Definition.Edge;
import static io.github.mchgood.flow.internal.graph.Definition.Node;
import static io.github.mchgood.flow.internal.graph.Definition.Type;

/**
 * 单进程、依赖就绪驱动的流程执行器，也是宿主可直接构造的标准实现。
 * <p>每个根调用由调用线程协调其整棵子执行树，业务任务和条件求值交给共享有界线程池。
 * 注册使用独立锁和不可变快照；每棵执行树的状态由 Root.lock 串行保护，不持锁调用业务代码。
 * 子流程与父流程共享协调器，不占用工作线程等待子流程，因此单工作线程也能执行嵌套流程。
 * 流程级拦截器仅在根流程上、节点级拦截器仅在 TASK 节点上触发，且全部钩子都在
 * 根协调锁外按装配顺序通知；前置拦截失败以 INTERCEPTOR_FAILED 快速失败，其余钩子异常只记录。
 * <p>逻辑结果发布后拒绝迟到写入；工作额度直到任务物理退出或成功移出队列才释放。
 * 输入和业务输出不深拷贝；节点及扩展实现必须满足并发使用约定。实例应由宿主生命周期统一关闭。
 */
public final class DefaultFlowEngine implements FlowEngine {
    private static final ThreadLocal<DefaultFlowEngine> CURRENT_ENGINE = new ThreadLocal<>();
    private static final System.Logger LOG = System.getLogger(DefaultFlowEngine.class.getName());

    private final FlowCompiler compiler;
    private final ConditionEvaluator evaluator;
    private final EngineConfig config;
    private final List<FlowExecutionInterceptor> flowInterceptors;
    private final List<NodeExecutionInterceptor> nodeInterceptors;
    private final ThreadPoolExecutor workerPool;
    private final Semaphore admissionPermits;
    private final Object registryLock = new Object();
    private volatile Map<String, Definition> registry = Map.of();
    private volatile boolean closed;
    private final Set<Root> roots = ConcurrentHashMap.newKeySet();

    /**
     * 创建使用默认资源配置的引擎。
     *
     * @param resolver 注册时使用的线程安全节点解析器
     * @param evaluator 线程安全条件编译与求值器
     * @throws NullPointerException 任一依赖为 null
     */
    public DefaultFlowEngine(NodeResolver resolver, ConditionEvaluator evaluator) {
        this(resolver, evaluator, EngineConfig.defaults());
    }

    /**
     * 创建拥有独立工作线程池和根调用准入额度的引擎。
     *
     * @param resolver 注册期节点绑定扩展
     * @param evaluator 注册期编译及执行期求值扩展
     * @param config 固定资源与期限配置
     * @throws NullPointerException 任一参数为 null
     */
    public DefaultFlowEngine(NodeResolver resolver, ConditionEvaluator evaluator, EngineConfig config) {
        this(resolver, evaluator, config, List.of(), List.of());
    }

    /**
     * 创建带执行拦截器的引擎；无拦截器需求时使用三参构造器。
     *
     * @param resolver 注册期节点绑定扩展
     * @param evaluator 注册期编译及执行期求值扩展
     * @param config 固定资源与期限配置
     * @param flowInterceptors 根流程拦截器，按列表顺序通知；复制保存，允许为空列表
     * @param nodeInterceptors 业务节点拦截器，按列表顺序通知；复制保存，允许为空列表
     * @throws NullPointerException 任一参数为 null 或列表含 null 元素
     */
    public DefaultFlowEngine(NodeResolver resolver, ConditionEvaluator evaluator, EngineConfig config,
            List<FlowExecutionInterceptor> flowInterceptors, List<NodeExecutionInterceptor> nodeInterceptors) {
        this.flowInterceptors = List.copyOf(flowInterceptors);
        this.nodeInterceptors = List.copyOf(nodeInterceptors);
        this.evaluator = Objects.requireNonNull(evaluator);
        this.config = Objects.requireNonNull(config);
        compiler = new FlowCompiler(Objects.requireNonNull(resolver), evaluator);
        admissionPermits = new Semaphore(config.maxConcurrentExecutions());
        var index = new AtomicInteger();
        workerPool = new ThreadPoolExecutor(config.workerThreads(), config.workerThreads(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "flow-worker-" + index.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FlowDescriptor register(String id, String markdown) {
        return registerAll(Map.of(id, markdown)).get(0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<FlowDescriptor> registerAll(Map<String, String> input) {
        Objects.requireNonNull(input);
        if (input.isEmpty()) {
            return List.of();
        }
        Map<String, Definition> candidates = new LinkedHashMap<>();
        input.forEach((id, text) -> candidates.put(id, compiler.compile(id, text)));
        synchronized (registryLock) {
            if (closed) {
                throw new FlowException("ENGINE_CLOSED", "Engine is closed");
            }
            Map<String, Definition> next = new LinkedHashMap<>(registry);
            candidates.forEach((id, definition) -> {
                if (next.putIfAbsent(id, definition) != null) {
                    throw new FlowException("DUPLICATE_FLOW", id);
                }
            });
            Map<String, Integer> depth = new HashMap<>();
            for (var id : next.keySet()) {
                referenceDepth(id, next, new LinkedHashSet<>(), depth);
            }
            registry = Collections.unmodifiableMap(next);
        }
        return candidates.values().stream().map(Definition::descriptor).toList();
    }

    /**
     * 以当前 DFS 路径识别引用环，以 memo 复用已校验子图深度。
     * 所有候选发布前一起检查，保证不会让缺失引用或过深调用进入运行阶段。
     */
    private int referenceDepth(String id, Map<String, Definition> definitions, Set<String> visiting,
            Map<String, Integer> memo) {
        if (memo.containsKey(id)) {
            return memo.get(id);
        }
        if (visiting.size() > config.maxSubflowDepth() + 1) {
            throw new FlowException("SUBFLOW_LIMIT_EXCEEDED", "Reference depth exceeded");
        }
        if (!visiting.add(id)) {
            throw new FlowException("FLOW_REFERENCE_CYCLE", String.join(" -> ", visiting) + " -> " + id);
        }
        Definition definition = definitions.get(id);
        if (definition == null) {
            throw new FlowException("SUBFLOW_NOT_FOUND", String.join(" -> ", visiting));
        }
        int maxDepth = 0;
        for (var node : definition.nodes.values()) {
            if (node.type == Type.CALL_FLOW) {
                maxDepth = Math.max(maxDepth, 1 + referenceDepth(node.target, definitions, visiting, memo));
            }
        }
        visiting.remove(id);
        if (maxDepth > config.maxSubflowDepth()) {
            throw new FlowException("SUBFLOW_LIMIT_EXCEEDED", "Static depth: " + id);
        }
        memo.put(id, maxDepth);
        return maxDepth;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public FlowResult execute(String id, Object input, ExecutionOptions options) {
        if (CURRENT_ENGINE.get() == this) {
            throw new FlowException("REENTRANT_EXECUTION", "Use an explicit subflow node");
        }
        Objects.requireNonNull(options);
        Root root;
        synchronized (registryLock) {
            if (closed) {
                throw new FlowException("ENGINE_CLOSED", "Engine is closed");
            }
            Definition definition = registry.get(id);
            if (definition == null) {
                throw new FlowException("FLOW_NOT_FOUND", id);
            }
            if (!admissionPermits.tryAcquire()) {
                throw new FlowException("FLOW_REJECTED", "Root capacity exhausted");
            }
            root = new Root(registry);
            Duration duration = options.timeout() == null ? config.flowTimeout() : options.timeout();
            root.main = new Execution(root, definition, input, null, null, System.nanoTime() + duration.toNanos());
            root.executions.add(root.main);
            roots.add(root);
        }
        for (var interceptor : flowInterceptors) {
            try {
                interceptor.beforeFlow(id, root.main.id, input);
            } catch (Throwable failure) {
                roots.remove(root);
                admissionPermits.release();
                if (failure instanceof VirtualMachineError virtualMachineError) {
                    throw virtualMachineError;
                }
                throw new FlowException("INTERCEPTOR_FAILED", "beforeFlow: " + id, failure);
            }
        }
        boolean interrupted = false;
        FlowResult result;
        root.lock.lock();
        try {
            while (root.main.result == null) {
                expire(root);
                boolean changed = pump(root);
                if (root.main.result != null) {
                    break;
                }
                if (changed) {
                    continue;
                }
                try {
                    root.wakeup.awaitNanos(waitNanos(root));
                } catch (InterruptedException exception) {
                    interrupted = true;
                    forceTree(root.main, FlowStatus.FAILED, "CALLER_INTERRUPTED");
                    settle(root);
                }
            }
            result = root.main.result;
        } finally {
            root.lock.unlock();
            roots.remove(root);
            admissionPermits.release();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        notifyFlow(result);
        return result;
    }

    /**
     * 一棵根执行树的协调状态。
     * <p>definitions 固定本次注册快照；executions、created、main 及所属节点状态由 lock 保护。
     * wakeup 用于完成通知及期限变化，工作线程释放锁后由调用线程继续推进。
     */
    private final class Root {
        final ReentrantLock lock = new ReentrantLock();
        final Condition wakeup = lock.newCondition();
        final Map<String, Definition> definitions;
        final List<Execution> executions = new ArrayList<>();
        Execution main;
        int created = 1;

        Root(Map<String, Definition> definitions) {
            this.definitions = definitions;
        }
    }

    /**
     * 单个根或子流程的隔离执行实例。
     * <p>节点结果和错误独立于父实例；输入仍是同一个对象引用。
     * inFlight 汇总当前实例及其后代的物理在途任务，所有可变字段由所属根锁保护。
     */
    private final class Execution {
        final Root root;
        final Definition definition;
        final Object input;
        final Execution parent;
        final RuntimeNode call;
        final String id = UUID.randomUUID().toString();
        final String path;
        final int depth;
        final Instant started = Instant.now();
        final long deadline;
        final Map<String, RuntimeNode> nodes = new LinkedHashMap<>();
        final Deque<RuntimeNode> ready = new ArrayDeque<>();
        final List<FlowError> errors = new ArrayList<>();
        boolean stopping;
        boolean forced;
        FlowStatus forcedStatus;
        FlowResult result;
        int inFlight;

        Execution(Root root, Definition definition, Object input, Execution parent, RuntimeNode call, long deadline) {
            this.root = root;
            this.definition = definition;
            this.input = input;
            this.parent = parent;
            this.call = call;
            this.deadline = deadline;
            depth = parent == null ? 0 : parent.depth + 1;
            path = parent == null ? definition.id : parent.path + "/" + call.spec.id + ":" + definition.id;
            definition.ordered.forEach(node -> nodes.put(node.id, new RuntimeNode(this, node)));
            ready.add(nodes.get("start"));
            log("flow-start", this, null, null);
        }
    }

    /**
     * 某次执行中的节点状态，spec 指向跨执行共享的不可变拓扑。
     * <p>remaining 统计尚未确定的入边，active 统计已激活入边，resolved 防止重复传播。
     * submitted 与 RUNNING 分开，避免排队等待被误算为任务执行时间。
     */
    private final class RuntimeNode {
        final Execution execution;
        final Node spec;
        int remaining;
        int active;
        NodeStatus status = NodeStatus.PENDING;
        Instant started;
        Instant ended;
        long deadline = Long.MAX_VALUE;
        String skip;
        String selected;
        Object value;
        FlowError error;
        Work work;
        Execution child;
        boolean submitted;
        final Set<String> resolved = new HashSet<>();

        RuntimeNode(Execution execution, Node node) {
            this.execution = execution;
            spec = node;
            remaining = node.incomingEdges.size();
        }

        NodeRecord record() {
            return new NodeRecord(spec.id, spec.target, spec.type.name(), status, status == NodeStatus.SUCCEEDED,
                    value, skip, error, started, ended, selected);
        }
    }

    /**
     * 在持有根锁时推进就绪节点和完成通知，返回是否发生进展。
     * <p>不按图层等待；当前没有额度的节点放回就绪队列，其他可执行节点仍可继续。
     * 遍历执行列表快照，以容纳本轮新创建的子流程。
     */
    private boolean pump(Root root) {
        boolean changed = false;
        for (var execution : new ArrayList<>(root.executions)) {
            if (execution.result != null || execution.forced) {
                continue;
            }
            if (!execution.stopping) {
                int count = execution.ready.size();
                while (count-- > 0 && !execution.stopping) {
                    var node = execution.ready.remove();
                    if (node.status != NodeStatus.PENDING || node.submitted) {
                        continue;
                    }
                    if (node.spec.type != Type.START) {
                        if (node.active == 0) {
                            skip(node, "BRANCH_NOT_SELECTED", true);
                            changed = true;
                            continue;
                        }
                        int expected = node.spec.type == Type.XOR_JOIN ? 1 : node.spec.incomingEdges.size();
                        if (node.active != expected) {
                            fail(node, NodeStatus.FAILED,
                                    node.spec.type == Type.XOR_JOIN ? "GATEWAY_CONFLICT" : "INPUT_PATH_MISMATCH",
                                    "Active input mismatch");
                            changed = true;
                            continue;
                        }
                    }
                    switch (node.spec.type) {
                        case TASK, XOR_SPLIT -> {
                            if (!hasSlot(execution)) {
                                execution.ready.add(node);
                                continue;
                            }
                            node.submitted = true;
                            adjustSlots(execution, 1);
                            node.work = new Work(node, context(node));
                            try {
                                workerPool.execute(node.work);
                            } catch (RejectedExecutionException rejection) {
                                node.work.release();
                                fail(node, NodeStatus.FAILED, "RESOURCE_REJECTED", "Worker queue full");
                            }
                        }
                        case CALL_FLOW -> {
                            startChild(node);
                        }
                        default -> {
                            node.started = Instant.now();
                            success(node, null, null);
                        }
                    }
                    changed = true;
                }
            }
        }
        return settle(root) || changed;
    }

    /**
     * 持根锁创建隔离子执行，并将调用节点置为 RUNNING。
     * 子期限取父剩余期限与默认流程期限的较小值；此处只建实例，不阻塞工作线程。
     */
    private void startChild(RuntimeNode node) {
        Execution parent = node.execution;
        Root root = parent.root;
        long active = root.executions.stream().filter(execution -> execution.parent != null
                && execution.result == null).count();
        if (root.created >= config.maxExecutionsPerRoot()
                || active >= config.maxActiveChildren() || parent.depth >= config.maxSubflowDepth()) {
            fail(node, NodeStatus.FAILED, "SUBFLOW_LIMIT_EXCEEDED", "Child execution limit");
            return;
        }
        node.status = NodeStatus.RUNNING;
        node.started = Instant.now();
        node.deadline = Math.min(parent.deadline, System.nanoTime() + config.flowTimeout().toNanos());
        node.child = new Execution(root, root.definitions.get(node.spec.target), parent.input, parent, node,
                node.deadline);
        root.created++;
        root.executions.add(node.child);
    }

    /**
     * 持根锁生成静态祖先快照；未终结祖先意味着调度不变量被破坏。
     */
    private NodeContext context(RuntimeNode node) {
        Map<String, NodeRecord> records = new LinkedHashMap<>();
        for (String id : node.spec.ancestors) {
            var ancestor = node.execution.nodes.get(id);
            if (ancestor.status == NodeStatus.PENDING || ancestor.status == NodeStatus.RUNNING) {
                throw new IllegalStateException("Unresolved ancestor " + id);
            }
            records.put(id, ancestor.record());
        }
        return new NodeContext(node.execution.id, node.execution.definition.id, node.spec.id,
                node.execution.input, records);
    }

    /**
     * 持根锁检查当前实例及所有祖先额度，防止嵌套流程绕过父级限额。
     */
    private boolean hasSlot(Execution execution) {
        for (; execution != null; execution = execution.parent) {
            if (execution.inFlight >= config.maxInFlightPerExecution()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 持根锁沿父链同时记账，delta 为提交时的 +1 或物理释放时的 -1。
     */
    private void adjustSlots(Execution execution, int delta) {
        for (; execution != null; execution = execution.parent) {
            execution.inFlight += delta;
        }
    }

    /**
     * 持根锁传播一次边状态：未选分支也必须传播“不激活”。
     * <p>只有所有入边状态已知才进入 ready；这样汇合不会永久等待未选择的分支。
     * resolved 保证同一条边最多减少一次 remaining。
     */
    private void publish(RuntimeNode from, String selected, boolean inactive) {
        for (var edge : from.spec.outgoingEdges) {
            var target = from.execution.nodes.get(edge.to.id);
            if (!target.resolved.add(edge.id)) {
                continue;
            }
            target.remaining--;
            if (!inactive && (selected == null || selected.equals(edge.id))) {
                target.active++;
            }
            if (target.remaining == 0) {
                from.execution.ready.add(target);
            }
        }
    }

    /**
     * 持根锁接受成功结果；若节点已终态或所属执行已强制终结则丢弃迟到结果。
     */
    private void success(RuntimeNode node, Object value, String selected) {
        if (node.execution.forced || node.execution.result != null || terminal(node.status)) {
            return;
        }
        node.status = NodeStatus.SUCCEEDED;
        node.value = value;
        node.selected = selected;
        node.ended = Instant.now();
        if (!node.execution.stopping) {
            publish(node, selected, false);
        }
        log("node-success", node.execution, node, null);
    }

    /**
     * 持根锁跳过尚未运行的节点；正常未选分支传播不激活，停止流程时不继续传播。
     */
    private void skip(RuntimeNode node, String reason, boolean propagate) {
        if (node.status != NodeStatus.PENDING) {
            return;
        }
        node.status = NodeStatus.SKIPPED;
        node.skip = reason;
        node.ended = Instant.now();
        if (node.work != null) {
            node.work.cancelWork();
        }
        if (propagate) {
            publish(node, null, true);
        }
    }

    /**
     * 持根锁写入单次失败、停止该执行的新任务并请求取消失败任务。
     * 已在运行的其他任务仍可能完成，普通失败不等于整棵树立即物理退出。
     */
    private void fail(RuntimeNode node, NodeStatus status, String code, String message) {
        if (terminal(node.status) || node.execution.result != null) {
            return;
        }
        node.status = status;
        node.ended = Instant.now();
        node.error = new FlowError(code, message, node.execution.id, node.spec.id, node.execution.path);
        node.execution.errors.add(node.error);
        log("node-failed", node.execution, node, code);
        stop(node.execution);
        if (node.work != null) {
            node.work.cancelWork();
        }
    }

    /**
     * 持根锁停止新调度，跳过所有待运行节点；不把已运行任务伪装为已退出。
     */
    private void stop(Execution execution) {
        execution.stopping = true;
        for (var node : execution.nodes.values()) {
            if (node.status == NodeStatus.PENDING) {
                skip(node, "FLOW_STOPPED", false);
            }
        }
        execution.ready.clear();
    }

    /**
     * 持根锁按子到父顺序收尾，并把子结果投递给父调用节点。
     * <p>正常收尾等待物理在途任务归零；强制收尾允许提前返回，同时记录未确认退出任务。
     * 结果创建一次后不再被后续线程修改。
     */
    private boolean settle(Root root) {
        boolean changed = false;
        for (int i = root.executions.size() - 1; i >= 0; i--) {
            var execution = root.executions.get(i);
            if (execution.result != null) {
                continue;
            }
            boolean pending = execution.nodes.values().stream().anyMatch(node -> node.status == NodeStatus.PENDING
                    || node.status == NodeStatus.RUNNING);
            if (!execution.forced && (pending || execution.inFlight > 0)) {
                continue;
            }
            FlowStatus status = execution.forced ? execution.forcedStatus
                    : (!execution.errors.isEmpty() ? FlowStatus.FAILED : FlowStatus.SUCCEEDED);
            if (status == FlowStatus.SUCCEEDED && execution.nodes.get("finish").status != NodeStatus.SUCCEEDED) {
                status = FlowStatus.FAILED;
                execution.errors.add(new FlowError("NO_ACTIVE_PATH", "Finish not reached", execution.id, "finish",
                        execution.path));
            }
            Map<String, NodeRecord> records = new LinkedHashMap<>();
            execution.nodes.forEach((id, node) -> records.put(id, node.record()));
            List<String> unfinished = new ArrayList<>();
            for (var candidate : root.executions) {
                if (descendant(candidate, execution)) {
                    for (var node : candidate.nodes.values()) {
                        if (node.work != null && !node.work.exited) {
                            unfinished.add(candidate.path + "/" + node.spec.id + "@" + candidate.id);
                        }
                    }
                }
            }
            execution.result = new FlowResult(execution.id, root.main.id,
                    execution.parent == null ? null : execution.parent.id, execution.definition.id,
                    execution.definition.hash, status, execution.started, Instant.now(), records, execution.errors,
                    unfinished);
            log("flow-end", execution, null, status.name());
            changed = true;
            if (execution.call != null && !terminal(execution.call.status) && execution.parent.result == null) {
                var call = execution.call;
                if (status == FlowStatus.SUCCEEDED) {
                    success(call, new ChildFlowResultView(status.name(), execution.id, execution.result.results()),
                            null);
                } else {
                    execution.parent.errors.addAll(execution.errors);
                    fail(call,
                            status == FlowStatus.TIMED_OUT ? NodeStatus.TIMED_OUT : NodeStatus.FAILED,
                            status == FlowStatus.TIMED_OUT ? "CHILD_FLOW_TIMEOUT" : "CHILD_FLOW_FAILED",
                            "Child " + execution.definition.id + " " + status);
                }
            }
        }
        return changed;
    }

    /**
     * 判断执行是否位于 ancestor 子树中，包含 ancestor 本身。
     */
    private boolean descendant(Execution execution, Execution ancestor) {
        for (; execution != null; execution = execution.parent) {
            if (execution == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * 持根锁用单调时钟检测期限，避免墙上时钟调整影响超时。
     * 流程超时强制终结子树；节点超时走节点失败路径。
     */
    private void expire(Root root) {
        long now = System.nanoTime();
        for (var execution : root.executions) {
            if (execution.result != null || execution.forced) {
                continue;
            }
            if (now - execution.deadline >= 0) {
                forceTree(execution, FlowStatus.TIMED_OUT, "FLOW_TIMEOUT");
                continue;
            }
            for (var node : execution.nodes.values()) {
                if (node.status == NodeStatus.RUNNING && node.spec.type != Type.CALL_FLOW
                        && now - node.deadline >= 0) {
                    fail(node, NodeStatus.TIMED_OUT, "NODE_TIMEOUT", "Node deadline exceeded");
                }
            }
        }
    }

    /**
     * 持根锁强制终结指定子树的逻辑状态，请求中断但不提前释放物理额度。
     */
    private void forceTree(Execution ancestor, FlowStatus status, String code) {
        for (var execution : ancestor.root.executions) {
            if (execution.result == null && descendant(execution, ancestor)) {
                execution.forced = true;
                execution.forcedStatus = status;
                stop(execution);
                for (var node : execution.nodes.values()) {
                    if (node.status == NodeStatus.RUNNING) {
                        node.status = status == FlowStatus.TIMED_OUT ? NodeStatus.TIMED_OUT : NodeStatus.FAILED;
                        node.ended = Instant.now();
                        node.error = new FlowError(code, "Execution terminated", execution.id, node.spec.id,
                                execution.path);
                        execution.errors.add(node.error);
                        if (node.work != null) {
                            node.work.cancelWork();
                        }
                    }
                }
                if (execution.errors.isEmpty()) {
                    execution.errors.add(new FlowError(code, "Execution terminated", execution.id, null,
                            execution.path));
                }
            }
        }
        ancestor.root.wakeup.signalAll();
    }

    /**
     * 持根锁计算最近期限的等待量，最多一秒并至少一纳秒，避免忙等或漏过期限。
     */
    private long waitNanos(Root root) {
        long now = System.nanoTime();
        long wait = TimeUnit.SECONDS.toNanos(1);
        for (var execution : root.executions) {
            if (execution.result == null && !execution.forced) {
                wait = Math.min(wait, execution.deadline - now);
                for (var node : execution.nodes.values()) {
                    if (node.status == NodeStatus.RUNNING) {
                        wait = Math.min(wait, node.deadline - now);
                    }
                }
            }
        }
        return Math.max(1, wait);
    }

    /**
     * 任务物理生命周期包装器，区分 Future 取消与业务代码真正退出。
     * <p>FutureTask.cancel 可先于用户代码退出完成，因此不在 done 回调释放额度。
     * run 的外层 finally 统一释放；成功移出队列时可直接释放，exited 保证幂等。
     */
    private final class Work extends FutureTask<Void> {
        final RuntimeNode node;
        boolean exited;

        Work(RuntimeNode node, NodeContext context) {
            super(() -> {
                runNode(node, context);
                return null;
            });
            this.node = node;
        }

        @Override
        public void run() {
            try {
                super.run();
            } finally {
                var root = node.execution.root;
                root.lock.lock();
                try {
                    release();
                } finally {
                    root.lock.unlock();
                }
            }
        }

        /**
         * 持根锁幂等释放当前任务沿祖先链的额度，并唤醒协调线程。
         */
        void release() {
            if (exited) {
                return;
            }
            exited = true;
            adjustSlots(node.execution, -1);
            node.execution.root.wakeup.signalAll();
        }

        /**
         * 持根锁请求中断；只有确认从工作队列移除时才能立即释放额度。
         */
        void cancelWork() {
            cancel(true);
            if (workerPool.remove(this)) {
                release();
            }
        }
    }

    /**
     * 工作线程的执行入口；先持锁确认准入，再解锁执行前置拦截、Bean 或整组条件。
     * <p>返回和异常都重新持锁先检查期限，防止迟到成功覆盖超时；所有条件都求值，
     * 多条为真时失败，不依赖 Mermaid 边顺序。ThreadLocal 阻止当前引擎的同步重入。
     * TASK 节点的前置拦截与终态通知均在锁外进行：前置拦截抛出的异常包装为
     * INTERCEPTOR_FAILED 走失败传播，后置与终态钩子异常只记录。
     */
    private void runNode(RuntimeNode node, NodeContext context) {
        Root root = node.execution.root;
        root.lock.lock();
        try {
            expire(root);
            if (node.execution.stopping || node.execution.forced || node.status != NodeStatus.PENDING) {
                return;
            }
            node.status = NodeStatus.RUNNING;
            node.started = Instant.now();
            node.deadline = Math.min(node.execution.deadline, System.nanoTime()
                    + (node.spec.type == Type.XOR_SPLIT ? config.gatewayTimeout() : config.nodeTimeout()).toNanos());
            root.wakeup.signalAll();
            log("node-start", node.execution, node, null);
        } finally {
            root.lock.unlock();
        }
        CURRENT_ENGINE.set(this);
        boolean notificationStarted = false;
        try {
            Object value;
            String selected = null;
            if (node.spec.type == Type.TASK) {
                for (var interceptor : nodeInterceptors) {
                    try {
                        interceptor.beforeNode(context);
                    } catch (Throwable failure) {
                        if (failure instanceof VirtualMachineError virtualMachineError) {
                            throw virtualMachineError;
                        }
                        throw new FlowException("INTERCEPTOR_FAILED", "beforeNode: " + node.spec.id, failure);
                    }
                }
                NodeOutcome stopped = stoppedBeforeBusiness(node);
                if (stopped != null) {
                    notificationStarted = true;
                    notifyNode(node, context, stopped);
                    return;
                }
                value = node.spec.bean.execute(context);
            } else {
                Edge match = null;
                Edge fallback = null;
                int matches = 0;
                for (var edge : node.spec.outgoingEdges) {
                    if (edge.fallback()) {
                        fallback = edge;
                        continue;
                    }
                    boolean matched = evaluator.evaluate(edge.condition, context);
                    if (matched) {
                        matches++;
                        match = edge;
                    }
                }
                if (matches > 1) {
                    throw new FlowException("CONDITION_CONFLICT", "Multiple conditions true at " + node.spec.id);
                }
                if (match == null) {
                    match = fallback;
                }
                if (match == null) {
                    throw new FlowException("NO_MATCHING_BRANCH", node.spec.id);
                }
                selected = match.id;
                value = null;
            }
            root.lock.lock();
            NodeOutcome outcome = null;
            try {
                expire(root);
                success(node, value, selected);
                root.wakeup.signalAll();
                outcome = outcomeOf(node);
            } finally {
                root.lock.unlock();
            }
            notificationStarted = true;
            notifyNode(node, context, outcome);
        } catch (Throwable failure) {
            // 通知已开始时的 VM 致命错误不能重新进入业务失败通知，否则钩子执行两次。
            if (notificationStarted && failure instanceof VirtualMachineError error) {
                throw error;
            }
            root.lock.lock();
            NodeOutcome outcome = null;
            try {
                expire(root);
                fail(node, NodeStatus.FAILED,
                        failure instanceof FlowException flowException ? flowException.code() : "NODE_FAILED",
                        failure.getMessage() == null
                                ? failure.getClass().getSimpleName()
                                : failure.getMessage());
                root.wakeup.signalAll();
                outcome = outcomeOf(node);
            } finally {
                root.lock.unlock();
            }
            notifyNode(node, context, outcome);
            if (failure instanceof VirtualMachineError error) {
                throw error;
            }
        } finally {
            CURRENT_ENGINE.remove();
        }
    }

    /**
     * 前置钩子返回后重新检查期限与停止状态，防止忽略中断的钩子继续启动业务。
     * <p>返回终态快照时通知仍在锁外进行；返回 null 表示本次检查允许开始业务。
     * 检查之后发生的取消仍是协作式的，不能原子地撤回已经开始的外部副作用。
     */
    private NodeOutcome stoppedBeforeBusiness(RuntimeNode node) {
        Root root = node.execution.root;
        root.lock.lock();
        try {
            expire(root);
            if (node.status == NodeStatus.RUNNING && node.execution.stopping) {
                fail(node, NodeStatus.FAILED, "FLOW_STOPPED", "Execution stopped before business call");
            }
            if (node.status != NodeStatus.RUNNING || node.execution.forced) {
                return outcomeOf(node);
            }
            return null;
        } finally {
            root.lock.unlock();
        }
    }

    /**
     * 判断逻辑终态；不能据此推断对应工作线程已退出。
     */
    private static boolean terminal(NodeStatus status) {
        return status != NodeStatus.PENDING && status != NodeStatus.RUNNING;
    }

    /**
     * 持根锁把节点实际终态固化为拦截器快照；非成功终态必然携带错误记录。
     */
    private NodeOutcome outcomeOf(RuntimeNode node) {
        if (node.error != null) {
            return new NodeOutcome(null, node.error.code(), node.error.message());
        }
        return new NodeOutcome(node.value, null, null);
    }

    /**
     * 根锁外按装配顺序通知流程级拦截器；afterFlow 必调，随后按终态互斥通知。
     * 钩子异常记录后忽略，不改变已发布终态；VirtualMachineError 照常上抛。
     */
    private void notifyFlow(FlowResult result) {
        for (var interceptor : flowInterceptors) {
            try {
                interceptor.afterFlow(result);
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError virtualMachineError) {
                    throw virtualMachineError;
                }
                LOG.log(System.Logger.Level.WARNING, "flow-interceptor-thrown afterFlow", failure);
            }
            try {
                if (result.succeeded()) {
                    interceptor.onSuccess(result);
                } else {
                    interceptor.onFailure(result);
                }
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError virtualMachineError) {
                    throw virtualMachineError;
                }
                LOG.log(System.Logger.Level.WARNING, "flow-interceptor-thrown terminal", failure);
            }
        }
    }

    /**
     * 根锁外按装配顺序通知节点级拦截器；仅 TASK 节点且已到达终态时触发，
     * afterNode 必调，随后按终态互斥通知。钩子异常记录后忽略，不改变节点与流程终态。
     */
    private void notifyNode(RuntimeNode node, NodeContext context, NodeOutcome outcome) {
        if (outcome == null || nodeInterceptors.isEmpty() || node.spec.type != Type.TASK) {
            return;
        }
        for (var interceptor : nodeInterceptors) {
            try {
                interceptor.afterNode(context, outcome);
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError virtualMachineError) {
                    throw virtualMachineError;
                }
                LOG.log(System.Logger.Level.WARNING, "node-interceptor-thrown afterNode", failure);
            }
            try {
                if (outcome.succeeded()) {
                    interceptor.onSuccess(context, outcome.value());
                } else {
                    interceptor.onFailure(context, outcome.errorCode(), outcome.message());
                }
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError virtualMachineError) {
                    throw virtualMachineError;
                }
                LOG.log(System.Logger.Level.WARNING, "node-interceptor-thrown terminal", failure);
            }
        }
    }

    /**
     * 记录执行标识和状态原因，不输出业务输入或结果内容。
     */
    private static void log(String event, Execution execution, RuntimeNode node, String reason) {
        LOG.log(System.Logger.Level.DEBUG, event + " execution=" + execution.id + " flow=" + execution.definition.id
                + " node=" + (node == null ? "" : node.spec.id) + " reason=" + reason);
    }

    /**
     * {@inheritDoc}
     * <p>等待根调用使用 closeTimeout；之后 shutdownNow 仅请求中断，并不 await 工作线程退出。
     * 重复关闭不会重新开放引擎。
     */
    @Override
    public void close() {
        if (CURRENT_ENGINE.get() == this) {
            throw new FlowException("REENTRANT_EXECUTION", "close from worker is unsupported");
        }
        synchronized (registryLock) {
            closed = true;
        }
        long end = System.nanoTime() + config.closeTimeout().toNanos();
        boolean interrupted = false;
        while (!roots.isEmpty() && System.nanoTime() < end) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException exception) {
                interrupted = true;
                break;
            }
        }
        for (var root : roots) {
            root.lock.lock();
            try {
                forceTree(root.main, FlowStatus.FAILED, "ENGINE_CLOSED");
                settle(root);
                root.wakeup.signalAll();
            } finally {
                root.lock.unlock();
            }
        }
        for (var runnable : workerPool.shutdownNow()) {
            if (runnable instanceof DefaultFlowEngine.Work work) {
                var root = work.node.execution.root;
                root.lock.lock();
                try {
                    work.cancel(false);
                    work.release();
                } finally {
                    root.lock.unlock();
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
