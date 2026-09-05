package io.github.mchgood.flow.internal.compiler;

import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.spi.CompiledCondition;
import io.github.mchgood.flow.spi.SourceLocation;


import java.util.*;
import io.github.mchgood.flow.internal.graph.Definition.Type;

final class MutableGraph {

    static final class Node {
        final String id,label,target; final SourceLocation location;
        Type type; FlowNode bean;
        final List<Edge> in=new ArrayList<>(),out=new ArrayList<>();
        final Set<String> ancestors=new LinkedHashSet<>();
        Node(String id,String label,String target,Type type,SourceLocation location){this.id=id;this.label=label;this.target=target;this.type=type;this.location=location;}
    }
    static final class Edge {
        final String id; final Node from,to; final String text;
        final SourceLocation location; CompiledCondition condition;
        Edge(Node from,Node to,String text,SourceLocation location){this.from=from;this.to=to;this.text=text;this.location=location;id=from.id+"->"+to.id;}
        boolean fallback(){return "default".equals(text);}
    }
}
