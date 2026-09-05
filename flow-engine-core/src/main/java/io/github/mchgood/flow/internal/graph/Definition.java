package io.github.mchgood.flow.internal.graph;

import io.github.mchgood.flow.api.FlowDescriptor;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.spi.CompiledCondition;
import io.github.mchgood.flow.spi.SourceLocation;
import java.util.*;

/** Immutable compiled topology. Internal cross-package contract, not a supported public API. */
public final class Definition {
    public enum Type { START, FINISH, TASK, CALL_FLOW, XOR_SPLIT, XOR_JOIN, AND_SPLIT, AND_JOIN }

    public record NodeSpec(String id, String label, String target, Type type,
                           SourceLocation location, FlowNode bean, Set<String> ancestors) {}
    public record EdgeSpec(String from, String to, String text,
                           SourceLocation location, CompiledCondition condition) {}

    public static final class Node {
        public final String id, label, target;
        public final Type type;
        public final SourceLocation location;
        public final FlowNode bean;
        public final Set<String> ancestors;
        public final List<Edge> in, out;

        private Node(NodeSpec spec, List<Edge> incoming, List<Edge> outgoing) {
            id = spec.id(); label = spec.label(); target = spec.target();
            type = spec.type(); location = spec.location(); bean = spec.bean();
            ancestors = Collections.unmodifiableSet(new LinkedHashSet<>(spec.ancestors()));
            in = Collections.unmodifiableList(incoming);
            out = Collections.unmodifiableList(outgoing);
        }
    }

    public static final class Edge {
        public final String id, text;
        public final Node from, to;
        public final SourceLocation location;
        public final CompiledCondition condition;

        private Edge(EdgeSpec spec, Map<String, Node> nodes) {
            from = Objects.requireNonNull(nodes.get(spec.from()));
            to = Objects.requireNonNull(nodes.get(spec.to()));
            id = from.id + "->" + to.id;
            text = spec.text(); location = spec.location(); condition = spec.condition();
        }

        public boolean fallback() { return "default".equals(text); }
    }

    public final String id, hash;
    public final Map<String, Node> nodes;
    public final List<Node> ordered;

    /** Copies compiler drafts; mutable adjacency builders never escape this constructor. */
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

    public FlowDescriptor descriptor() { return new FlowDescriptor(id, hash, nodes.size()); }
}
