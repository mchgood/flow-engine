package io.github.mchgood.flow.internal.graph;

import io.github.mchgood.flow.api.FlowDescriptor;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.spi.CompiledCondition;
import io.github.mchgood.flow.spi.SourceLocation;
import java.util.*;

/**
 * 跨编译器和运行时共享的不可变执行拓扑。
 * <p>构造时复制节点描述和祖先集合，构建内部邻接表并只暴露只读视图。
 * 不保存执行状态；Bean 和编译条件只保留引用，其线程安全由对应扩展契约保证。
 * 本类仅为 internal 跨包协作而公开，构造者应提供已校验的唯一节点和合法边。
 */
public final class Definition {

    /**
     * 由图形和入出度确定的执行语义；不由节点显示标签中的业务文字推断。
     */
    public enum Type {
        /** 虚拟开始节点。 */
        START,
        /** 虚拟结束节点。 */
        FINISH,
        /** 绑定 Bean 的业务任务。 */
        TASK,
        /** 按流程 ID 创建子执行。 */
        CALL_FLOW,
        /** 排他选择：唯一条件匹配或默认边。 */
        XOR_SPLIT,
        /** 排他汇合：待入边确定后要求恰好一路激活。 */
        XOR_JOIN,
        /** 并行分叉：激活全部出边。 */
        AND_SPLIT,
        /** 并行汇合：要求全部入边激活。 */
        AND_JOIN
    }

    /**
     * 传入图构造器的节点描述；本 record 自身不冻结 ancestors，构造 Definition 时复制。
     *
     * @param id 完整节点 ID
     * @param label 显示标签
     * @param target 任务 Bean ID 或子流程 ID，控制节点为 null
     * @param type 执行语义
     * @param location 定义位置
     * @param bean 任务实现，非任务节点为 null
     * @param ancestors 静态祖先节点 ID 集合
     */
    public record NodeSpec(String id, String label, String target, Type type,
                           SourceLocation location, FlowNode bean, Set<String> ancestors) {}

    /**
     * 传入图构造器的边描述；默认边没有编译条件。
     *
     * @param from 源节点 ID
     * @param to 目标节点 ID
     * @param text 原始条件文本、default 或 null
     * @param location 定义位置
     * @param condition 预编译条件，默认或普通边为 null
     */
    public record EdgeSpec(String from, String to, String text,
                           SourceLocation location, CompiledCondition condition) {}

    /**
     * 不可变节点拓扑；邻接边连接本 Definition 内的节点，祖先集合用于数据可见性。
     */
    public static final class Node {

        /**
         * 完整图 ID、显示标签与去别名后的调用目标；控制节点 target 为 null。
         */
        public final String id, label, target;

        /**
         * 节点执行语义。
         */
        public final Type type;

        /**
         * 原始定义中的诊断位置。
         */
        public final SourceLocation location;

        /**
         * 绑定的任务 Bean 引用；非任务节点为 null。
         */
        public final FlowNode bean;

        /**
         * 不可修改的静态祖先 ID 集合。
         */
        public final Set<String> ancestors;

        /**
         * 不可修改的入边、出边邻接表。
         */
        public final List<Edge> in, out;

        private Node(NodeSpec spec, List<Edge> incoming, List<Edge> outgoing) {
            id = spec.id(); label = spec.label(); target = spec.target();
            type = spec.type(); location = spec.location(); bean = spec.bean();
            ancestors = Collections.unmodifiableSet(new LinkedHashSet<>(spec.ancestors()));
            in = Collections.unmodifiableList(incoming);
            out = Collections.unmodifiableList(outgoing);
        }
    }

    /**
     * 不可变有向边；相同 from/to 只允许一条，因此可用端点组合唯一标识。
     */
    public static final class Edge {

        /**
         * 端点组合的边 ID 与原始标签；无标签时 text 为 null。
         */
        public final String id, text;

        /**
         * 当前图内的源节点与目标节点。
         */
        public final Node from, to;

        /**
         * 原始定义中的诊断位置。
         */
        public final SourceLocation location;

        /**
         * 条件预编译对象；普通边及默认边为 null。
         */
        public final CompiledCondition condition;

        private Edge(EdgeSpec spec, Map<String, Node> nodes) {
            from = Objects.requireNonNull(nodes.get(spec.from()));
            to = Objects.requireNonNull(nodes.get(spec.to()));
            id = from.id + "->" + to.id;
            text = spec.text(); location = spec.location(); condition = spec.condition();
        }

        /**
         * 判断是否是排他网关的保留默认边。
         *
         * @return 原始标签严格等于 default 时为 true
         */
        public boolean fallback() { return "default".equals(text); }
    }

    /**
     * 流程 ID 与原始 Markdown 的 SHA-256 摘要。
     */
    public final String id, hash;

    /**
     * 按完整节点 ID 索引的只读节点表。
     */
    public final Map<String, Node> nodes;

    /**
     * 只读拓扑顺序，用于初始化执行实例。
     */
    public final List<Node> ordered;

    /**
     * 复制已校验草稿并构造只读图；邻接表的可变构建引用不会逃逸。
     *
     * @param id 流程 ID
     * @param hash 原始 Markdown 摘要
     * @param orderedSpecs 已按拓扑顺序排列的节点描述
     * @param edges 已校验的边描述
     * @throws NullPointerException 集合为 null、祖先集合为 null 或边引用不存在的节点
     */
    public Definition(String id, String hash, List<NodeSpec> orderedSpecs, List<EdgeSpec> edges) {
        this.id = id;
        this.hash = hash;
        Map<String, Node> compiled = new LinkedHashMap<>();
        Map<String, List<Edge>> incoming = new HashMap<>();
        Map<String, List<Edge>> outgoing = new HashMap<>();
        for (NodeSpec spec : orderedSpecs) {
            List<Edge> in = new ArrayList<>(), out = new ArrayList<>();
            incoming.put(spec.id(), in);
            outgoing.put(spec.id(), out);
            compiled.put(spec.id(), new Node(spec, in, out));
        }
        for (EdgeSpec spec : edges) {
            Edge edge = new Edge(spec, compiled);
            outgoing.get(spec.from()).add(edge);
            incoming.get(spec.to()).add(edge);
        }
        nodes = Collections.unmodifiableMap(compiled);
        ordered = List.copyOf(compiled.values());
    }

    /**
     * 生成不泄漏内部拓扑的注册描述。
     *
     * @return 流程 ID、摘要和节点数
     */
    public FlowDescriptor descriptor() { return new FlowDescriptor(id, hash, nodes.size()); }
}
