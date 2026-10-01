package io.github.mchgood.flow.internal.compiler;

import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.internal.graph.Definition;
import io.github.mchgood.flow.internal.graph.Definition.Type;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.NodeResolver;
import io.github.mchgood.flow.spi.SourceLocation;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.github.mchgood.flow.internal.compiler.MutableGraph.Edge;
import static io.github.mchgood.flow.internal.compiler.MutableGraph.Node;

/**
 * 把受限 Markdown/Mermaid 定义编译成可执行的不可变图。
 * <p>按顺序提取代码块、解析声明与边、检查拓扑和网关区域、计算祖先集合并绑定业务节点。
 * 所有可变草稿只属于本次 compile 调用；注册表发布和跨流程引用校验由运行时负责。
 * 此类位于 internal 包，public 仅为跨包协作，不是宿主应直接依赖的稳定 API。
 */
public final class FlowCompiler {

    private final NodeResolver resolver;
    private final ConditionEvaluator evaluator;

    /**
     * 创建编译器，扩展依赖应支持并发注册。
     *
     * @param resolver 任务绑定器
     * @param evaluator 条件编译器
     */
    public FlowCompiler(NodeResolver resolver, ConditionEvaluator evaluator) {
        this.resolver = resolver;
        this.evaluator = evaluator;
    }

    /**
     * 单行解析得到的节点声明；shape 为 null 表示仅引用已有节点，尚无显式形状。
     */
    private record Decl(String id, String label, String shape, SourceLocation location) {
    }

    /**
     * 编译单个流程；不发布定义，也不检查目标子流程是否已注册。
     *
     * @param id 小驼峰流程 ID
     * @param markdown 原始 Markdown，最多 1 MiB UTF-8 字节
     * @return 复制草稿后的不可变拓扑
     * @throws FlowException 语法、资源上限、图结构、条件或 Bean 绑定校验失败
     */
    public Definition compile(String id, String markdown) {
        if (id == null || !id.matches("[a-z][A-Za-z0-9]*")) {
            throw new FlowException("INVALID_FLOW_ID", "Expected lower camel case: " + id);
        }
        if (markdown == null || markdown.getBytes(StandardCharsets.UTF_8).length > 1_048_576) {
            throw new FlowException("DEFINITION_LIMIT", "Markdown missing or too large");
        }
        var ast = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build().
                parse(markdown.replaceFirst("^\\uFEFF", ""));
        List<FencedCodeBlock> blocks = new ArrayList<>();
        ast.accept(new AbstractVisitor() {
            @Override
            public void visit(FencedCodeBlock block) {
                if ("mermaid".equals(block.getInfo().trim())) {
                    blocks.add(block);
                }
            }
        });
        if (blocks.size() != 1) {
            throw new FlowException("MERMAID_BLOCK_COUNT", "Expected one mermaid block in " + id);
        }
        var block = blocks.get(0);
        if (!(block.getParent() instanceof Document)) {
            throw new FlowException("MERMAID_BLOCK_LOCATION", "Mermaid block must be top level");
        }
        int base = block.getSourceSpans().isEmpty() ? 1 : block.getSourceSpans().get(0).getLineIndex() + 2;
        String[] lines = block.getLiteral().split("\\R", -1);
        Map<String, Decl> declarations = new LinkedHashMap<>();
        List<String[]> links = new ArrayList<>();
        List<SourceLocation> locations = new ArrayList<>();
        boolean header = false;
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            var cursor = new Cursor(id, lines[lineIndex], base + lineIndex);
            cursor.space();
            if (cursor.end()) {
                continue;
            }
            if (cursor.rest().startsWith("%%")) {
                if (cursor.rest().matches("%%\\s*@bean.*")) {
                    throw cursor.error("DEPRECATED_BINDING", "Use beanId_alias instead");
                }
                if (cursor.rest().startsWith("%%{")) {
                    throw cursor.error("UNSUPPORTED_SYNTAX", "Directives are unsupported");
                }
                continue;
            }
            if (!header) {
                if (!cursor.rest().matches("flowchart\\s+(TD|LR)\\s*")) {
                    throw cursor.error("INVALID_HEADER", "Expected flowchart TD or LR");
                }
                header = true;
                continue;
            }
            Decl from = cursor.node();
            merge(declarations, from);
            while (true) {
                cursor.space();
                if (cursor.end()) {
                    break;
                }
                cursor.expect("-->");
                cursor.space();
                String condition = null;
                if (cursor.take("|")) {
                    cursor.expect("\"");
                    condition = cursor.until("\"");
                    cursor.expect("|");
                    cursor.space();
                    if (condition.isBlank()) {
                        throw cursor.error("EMPTY_CONDITION", "Empty edge condition");
                    }
                }
                Decl to = cursor.node();
                merge(declarations, to);
                links.add(new String[] {from.id, to.id, condition});
                locations.add(from.location());
                from = to;
                if (links.size() > 4096) {
                    throw cursor.error("DEFINITION_LIMIT", "Too many edges");
                }
            }
        }
        if (!header || declarations.size() > 512) {
            throw new FlowException("DEFINITION_LIMIT", "Invalid header or too many nodes");
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (var declaration : declarations.values()) {
            Type type;
            String target = null;
            if (declaration.id.equals("start") || declaration.id.equals("finish")) {
                if (declaration.shape != null && !declaration.shape.equals("event")) {
                    throw error("NODE_SHAPE", declaration.location(), "Reserved start/finish shape");
                }
                type = declaration.id.equals("start") ? Type.START : Type.FINISH;
            } else {
                type = switch (declaration.shape == null ? "task" : declaration.shape) {
                    case "task" -> Type.TASK;
                    case "call" -> Type.CALL_FLOW;
                    case "diamond" -> declaration.label.equals("+") ? Type.AND_SPLIT : Type.XOR_SPLIT;
                    default -> throw error("NODE_SHAPE", declaration.location(), "Unsupported event");
                };
                if (type == Type.TASK || type == Type.CALL_FLOW) {
                    if (!declaration.id.matches("[a-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+(?:_[A-Za-z0-9]+)*)?")) {
                        throw error("INVALID_NODE_ID", declaration.location(), declaration.id);
                    }
                    target = declaration.id.split("_", 2)[0];
                }
            }
            nodes.put(declaration.id,
                    new Node(declaration.id, declaration.label == null ? declaration.id : declaration.label,
                            target, type, declaration.location()));
        }
        Set<String> edges = new HashSet<>();
        for (int i = 0; i < links.size(); i++) {
            var link = links.get(i);
            Node from = nodes.get(link[0]);
            Node to = nodes.get(link[1]);
            var edge = new Edge(from, to, link[2], locations.get(i));
            if (from == to || !edges.add(edge.id)) {
                throw error("DUPLICATE_OR_SELF_EDGE", edge.location, edge.id);
            }
            from.outgoingEdges.add(edge);
            to.incomingEdges.add(edge);
        }
        Node start = nodes.get("start");
        Node finish = nodes.get("finish");
        if (start == null || finish == null || !start.incomingEdges.isEmpty() || start.outgoingEdges.isEmpty()
                || !finish.outgoingEdges.isEmpty() || finish.incomingEdges.isEmpty()) {
            throw new FlowException("INVALID_ENDPOINTS", id);
        }
        if (nodes.values().stream().noneMatch(node -> node.type == Type.TASK || node.type == Type.CALL_FLOW)) {
            throw new FlowException("EMPTY_FLOW", id);
        }
        for (var node : nodes.values()) {
            if (node.type == Type.XOR_SPLIT || node.type == Type.AND_SPLIT) {
                if (node.incomingEdges.size() >= 2 && node.outgoingEdges.size() == 1) {
                    node.type = node.type == Type.XOR_SPLIT ? Type.XOR_JOIN : Type.AND_JOIN;
                } else if (node.incomingEdges.size() != 1 || node.outgoingEdges.size() < 2) {
                    throw error("GATEWAY_DEGREE", node.location, node.id);
                }
            }
            if (node.type == Type.XOR_SPLIT) {
                if (node.outgoingEdges.size() > 32) {
                    throw error("DEFINITION_LIMIT", node.location, "Gateway edge limit");
                }
                long defaults = node.outgoingEdges.stream().filter(Edge::fallback).count();
                if (defaults > 1) {
                    throw error("MULTIPLE_DEFAULTS", node.location, node.id);
                }
                for (var edge : node.outgoingEdges) {
                    if (edge.text == null) {
                        throw error("MISSING_CONDITION", edge.location, edge.id);
                    }
                    if (!edge.fallback()) {
                        edge.condition = evaluator.parse(edge.text, edge.location);
                    }
                }
            } else if (node.outgoingEdges.stream().anyMatch(edge -> edge.text != null)) {
                throw error("CONDITION_NOT_ALLOWED", node.location, node.id);
            }
        }
        Map<Node, Integer> degrees = new IdentityHashMap<>();
        Deque<Node> ready = new ArrayDeque<>();
        nodes.values().forEach(node -> {
            degrees.put(node, node.incomingEdges.size());
            if (node.incomingEdges.isEmpty()) {
                ready.add(node);
            }
        });
        List<Node> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            var node = ready.remove();
            order.add(node);
            for (var edge : node.outgoingEdges) {
                if (degrees.compute(edge.to, (k, remaining) -> remaining - 1) == 0) {
                    ready.add(edge.to);
                }
            }
        }
        if (order.size() != nodes.size()) {
            throw new FlowException("GRAPH_CYCLE", id);
        }
        Set<Node> forward = walk(start, false, null);
        Set<Node> backward = walk(finish, true, null);
        if (forward.size() != nodes.size() || backward.size() != nodes.size()) {
            throw new FlowException("UNREACHABLE_NODE", id);
        }
        for (var node : order) {
            for (var edge : node.incomingEdges) {
                node.ancestors.addAll(edge.from.ancestors);
                node.ancestors.add(edge.from.id);
            }
        }
        validateExclusiveRegions(order, finish);
        for (var node : nodes.values()) {
            if (node.type == Type.TASK) {
                node.bean = resolver.resolve(node.target);
                if (node.bean == null) {
                    throw error("BEAN_NOT_FOUND", node.location, node.target);
                }
            }
        }
        try {
            return new Definition(id,
                    HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(markdown.getBytes(StandardCharsets.UTF_8))),
                    order.stream().map(node -> new Definition.NodeSpec(node.id, node.label, node.target, node.type,
                            node.location, node.bean, node.ancestors)).toList(),
                    nodes.values().stream().flatMap(node -> node.outgoingEdges.stream()).
                            map(edge -> new Definition.EdgeSpec(edge.from.id, edge.to.id, edge.text, edge.location,
                                    edge.condition)).toList());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 利用后支配集合寻找每个排他分叉最近的公共汇合点。
     * <p>要求分支在配对汇合前互不重叠、无外部入口出口，并只有一个汇合出口；
     * 这是本框架对可执行 DAG 的结构约束，不等同于通用 BPMN token 透传语义。
     */
    private void validateExclusiveRegions(List<Node> order, Node finish) {
        Map<Node, Set<Node>> post = new IdentityHashMap<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            var node = order.get(i);
            Set<Node> shared = new HashSet<>();
            if (!node.outgoingEdges.isEmpty()) {
                shared.addAll(post.get(node.outgoingEdges.get(0).to));
                for (var edge : node.outgoingEdges) {
                    shared.retainAll(post.get(edge.to));
                }
            }
            shared.add(node);
            post.put(node, shared);
        }
        Set<Node> paired = new HashSet<>();
        for (var split : order) {
            if (split.type == Type.XOR_SPLIT) {
                Node join = order.stream().filter(node -> node != split && post.get(split).contains(node)).
                        findFirst().orElse(null);
                if (join == null || join.type != Type.XOR_JOIN || !paired.add(join)) {
                    throw error("GATEWAY_STRUCTURE_INVALID", split.location, split.id);
                }
                Set<Node> union = new HashSet<>();
                for (var edge : split.outgoingEdges) {
                    Set<Node> region = walk(edge.to, false, join);
                    for (var node : region) {
                        if (!union.add(node)) {
                            throw error("GATEWAY_STRUCTURE_INVALID", node.location, "Branches overlap before join");
                        }
                    }
                    for (var node : region) {
                        if (node.incomingEdges.stream().
                                anyMatch(incoming -> incoming.from != split && !region.contains(incoming.from))) {
                            throw error("GATEWAY_STRUCTURE_INVALID", node.location, "External branch entry");
                        }
                        if (node.outgoingEdges.stream().
                                anyMatch(outgoing -> outgoing.to != join && !region.contains(outgoing.to))) {
                            throw error("GATEWAY_STRUCTURE_INVALID", node.location, "External branch exit");
                        }
                    }
                    long exits = edge.to == join ? 1
                            : region.stream().flatMap(node -> node.outgoingEdges.stream()).
                                    filter(outgoing -> outgoing.to == join).count();
                    if (exits != 1) {
                        throw error("GATEWAY_STRUCTURE_INVALID", split.location,
                                "Parallel branch must join before XOR join");
                    }
                }
                if (join.incomingEdges.stream().anyMatch(edge -> edge.from != split && !union.contains(edge.from))) {
                    throw error("GATEWAY_STRUCTURE_INVALID", join.location, "Unrelated join input");
                }
            }
        }
        for (var node : order) {
            if (node.type == Type.XOR_JOIN && !paired.contains(node)) {
                throw error("GATEWAY_STRUCTURE_INVALID", node.location, "Unpaired XOR join");
            }
        }
    }

    /**
     * 沿正向或反向边收集可达节点；到 stop 即停止且不包含 stop。
     */
    private static Set<Node> walk(Node node, boolean reverse, Node stop) {
        Set<Node> result = new HashSet<>();
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(node);
        while (!queue.isEmpty()) {
            var current = queue.remove();
            if (current == stop || !result.add(current)) {
                continue;
            }
            for (var edge : reverse ? current.incomingEdges : current.outgoingEdges) {
                queue.add(reverse ? edge.from : edge.to);
            }
        }
        return result;
    }

    /**
     * 合并重复节点引用；允许后续补充形状，拒绝互相冲突的显式声明。
     */
    private static void merge(Map<String, Decl> map, Decl declaration) {
        Decl old = map.get(declaration.id);
        if (old == null || old.shape == null) {
            map.put(declaration.id, declaration);
        } else if (declaration.shape != null
                && (!old.shape.equals(declaration.shape) || !Objects.equals(old.label, declaration.label))) {
            throw error("CONFLICTING_NODE", declaration.location(), declaration.id);
        }
    }

    /**
     * 将结构化源码位置附入诊断文本，错误码仍单独保留。
     */
    static FlowException error(String code, SourceLocation location, String text) {
        return new FlowException(code, location + " " + text);
    }

    /**
     * 单行 Mermaid 游标，保留 Markdown 行列位置。
     * 只识别约定的节点形状、箭头和双引号标签，不尝试兼容完整 Mermaid 语法。
     */
    private static final class Cursor {

        final String source;
        final String line;
        final int number;
        int position;

        Cursor(String source, String line, int number) {
            this.source = source;
            this.line = line;
            this.number = number;
        }

        void space() {
            while (!end() && Character.isWhitespace(line.charAt(position))) {
                position++;
            }
        }

        boolean end() { return position >= line.length(); }

        String rest() { return line.substring(position); }

        boolean take(String text) {
            if (line.startsWith(text, position)) {
                position += text.length();
                return true;
            }
            return false;
        }

        void expect(String text) {
            if (!take(text)) {
                throw error("UNSUPPORTED_SYNTAX", "Expected " + text);
            }
        }

        /**
         * 读取直到给定终止符并消费终止符；不支持标签内转义语法。
         */
        String until(String end) {
            int start = position;
            while (!this.end() && !line.startsWith(end, position)) {
                position++;
            }
            if (this.end()) {
                throw error("UNCLOSED_LABEL", end);
            }
            String result = line.substring(start, position);
            position += end.length();
            return result;
        }

        FlowException error(String code, String detail) {
            return FlowCompiler.error(code, new SourceLocation(source, number, position + 1), detail);
        }

        /**
         * 解析完整节点 ID 与可选形状；此处保留别名，目标 ID 在后续类型解析时分离。
         */
        Decl node() {
            space();
            int start = position;
            if (end() || !(Character.isLetter(line.charAt(position)) || line.charAt(position) == '_')) {
                throw error("INVALID_NODE_ID", "Expected node ID");
            }
            while (!end() && (Character.isLetterOrDigit(line.charAt(position)) || line.charAt(position) == '_')) {
                position++;
            }
            String id = line.substring(start, position);
            if (!id.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw error("INVALID_NODE_ID", id);
            }
            SourceLocation location = new SourceLocation(source, number, start + 1);
            space();
            String label = null;
            String shape = null;
            if (take("[[\"")) {
                label = until("\"]]");
                shape = "call";
            } else if (take("[\"")) {
                label = until("\"]");
                shape = "task";
            } else if (take("{\"")) {
                label = until("\"}");
                shape = "diamond";
            } else if (take("([")) {
                label = until("])");
                shape = "event";
            }
            return new Decl(id, label, shape, location);
        }
    }
}
