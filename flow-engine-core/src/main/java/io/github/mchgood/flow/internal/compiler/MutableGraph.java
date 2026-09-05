package io.github.mchgood.flow.internal.compiler;

import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.spi.CompiledCondition;
import io.github.mchgood.flow.spi.SourceLocation;


import java.util.*;
import io.github.mchgood.flow.internal.graph.Definition.Type;

/**
 * 仅供单次编译使用的可变图草稿容器，不对运行时暴露。
 * <p>边连接、网关类型修正、祖先集合和 Bean 绑定在编译期逐步填充，完成后复制为 Definition。
 */
final class MutableGraph {

    /**
     * 编译期节点草稿；入出边和祖先集合允许原地追加，仅由本次编译线程访问。
     */
    static final class Node {
        final String id,label,target; final SourceLocation location;
        Type type; FlowNode<?> bean;
        final List<Edge> in=new ArrayList<>(),out=new ArrayList<>();
        final Set<String> ancestors=new LinkedHashSet<>();
        Node(String id,String label,String target,Type type,SourceLocation location){this.id=id;this.label=label;this.target=target;this.type=type;this.location=location;}
    }

    /**
     * 编译期边草稿；条件文本保留源码位置，condition 在校验时预解析。
     */
    static final class Edge {
        final String id; final Node from,to; final String text;
        final SourceLocation location; CompiledCondition condition;
        Edge(Node from,Node to,String text,SourceLocation location){this.from=from;this.to=to;this.text=text;this.location=location;id=from.id+"->"+to.id;}
        boolean fallback(){return "default".equals(text);}
    }
}
