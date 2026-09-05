package io.github.mchgood.flow.engine;

import io.github.mchgood.flow.*;

import java.util.*;

final class Definition {
    enum Type { START, FINISH, TASK, CALL_FLOW, XOR_SPLIT, XOR_JOIN, AND_SPLIT, AND_JOIN }
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
    final String id,hash;
    final Map<String,Node> nodes;
    final List<Node> ordered;
    Definition(String id,String hash,Map<String,Node> nodes,List<Node> ordered){this.id=id;this.hash=hash;this.nodes=Collections.unmodifiableMap(new LinkedHashMap<>(nodes));this.ordered=List.copyOf(ordered);}
    FlowDescriptor descriptor(){return new FlowDescriptor(id,hash,nodes.size());}
}
