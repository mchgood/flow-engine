package io.github.mchgood.flow.internal.compiler;

import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.NodeResolver;
import io.github.mchgood.flow.spi.SourceLocation;


import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.parser.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static io.github.mchgood.flow.internal.compiler.MutableGraph.*;
import io.github.mchgood.flow.internal.graph.Definition;
import io.github.mchgood.flow.internal.graph.Definition.Type;

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
    public FlowCompiler(NodeResolver resolver,ConditionEvaluator evaluator){this.resolver=resolver;this.evaluator=evaluator;}

    /**
     * 单行解析得到的节点声明；shape 为 null 表示仅引用已有节点，尚无显式形状。
     */
    private record Decl(String id,String label,String shape,SourceLocation loc){}

    /**
     * 编译单个流程；不发布定义，也不检查目标子流程是否已注册。
     *
     * @param id 小驼峰流程 ID
     * @param markdown 原始 Markdown，最多 1 MiB UTF-8 字节
     * @return 复制草稿后的不可变拓扑
     * @throws FlowException 语法、资源上限、图结构、条件或 Bean 绑定校验失败
     */
    public Definition compile(String id,String markdown){
        if(id==null||!id.matches("[a-z][A-Za-z0-9]*"))throw new FlowException("INVALID_FLOW_ID","Expected lower camel case: "+id);
        if(markdown==null||markdown.getBytes(StandardCharsets.UTF_8).length>1_048_576)throw new FlowException("DEFINITION_LIMIT","Markdown missing or too large");
        var ast=Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build().parse(markdown.replaceFirst("^\\uFEFF",""));
        List<FencedCodeBlock> blocks=new ArrayList<>();
        ast.accept(new AbstractVisitor(){@Override public void visit(FencedCodeBlock b){if("mermaid".equals(b.getInfo().trim()))blocks.add(b);}});
        if(blocks.size()!=1)throw new FlowException("MERMAID_BLOCK_COUNT","Expected one mermaid block in "+id);
        var block=blocks.get(0);
        if(!(block.getParent() instanceof Document))throw new FlowException("MERMAID_BLOCK_LOCATION","Mermaid block must be top level");
        int base=block.getSourceSpans().isEmpty()?1:block.getSourceSpans().get(0).getLineIndex()+2;
        String[] lines=block.getLiteral().split("\\R",-1);
        Map<String,Decl> declarations=new LinkedHashMap<>();
        List<String[]> links=new ArrayList<>();List<SourceLocation> locations=new ArrayList<>();
        boolean header=false;
        for(int l=0;l<lines.length;l++){
            var c=new Cursor(id,lines[l],base+l);c.space();if(c.end())continue;
            if(c.rest().startsWith("%%")){
                if(c.rest().matches("%%\\s*@bean.*"))throw c.error("DEPRECATED_BINDING","Use beanId_alias instead");
                if(c.rest().startsWith("%%{"))throw c.error("UNSUPPORTED_SYNTAX","Directives are unsupported");
                continue;
            }
            if(!header){if(!c.rest().matches("flowchart\\s+(TD|LR)\\s*"))throw c.error("INVALID_HEADER","Expected flowchart TD or LR");header=true;continue;}
            Decl from=c.node();merge(declarations,from);
            while(true){
                c.space();if(c.end())break;
                c.expect("-->");c.space();String condition=null;
                if(c.take("|")){c.expect("\"");condition=c.until("\"");c.expect("|");c.space();if(condition.isBlank())throw c.error("EMPTY_CONDITION","Empty edge condition");}
                Decl to=c.node();merge(declarations,to);
                links.add(new String[]{from.id,to.id,condition});locations.add(from.loc);from=to;
                if(links.size()>4096)throw c.error("DEFINITION_LIMIT","Too many edges");
            }
        }
        if(!header||declarations.size()>512)throw new FlowException("DEFINITION_LIMIT","Invalid header or too many nodes");
        Map<String,Node> nodes=new LinkedHashMap<>();
        for(var d:declarations.values()){
            Type type;String target=null;
            if(d.id.equals("start")||d.id.equals("finish")){
                if(d.shape!=null&&!d.shape.equals("event"))throw error("NODE_SHAPE",d.loc,"Reserved start/finish shape");
                type=d.id.equals("start")?Type.START:Type.FINISH;
            } else {
                type=switch(d.shape==null?"task":d.shape){case "task"->Type.TASK;case "call"->Type.CALL_FLOW;case "diamond"->d.label.equals("+")?Type.AND_SPLIT:Type.XOR_SPLIT;default->throw error("NODE_SHAPE",d.loc,"Unsupported event");};
                if(type==Type.TASK||type==Type.CALL_FLOW){
                    if(!d.id.matches("[a-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+(?:_[A-Za-z0-9]+)*)?"))throw error("INVALID_NODE_ID",d.loc,d.id);
                    target=d.id.split("_",2)[0];
                }
            }
            nodes.put(d.id,new Node(d.id,d.label==null?d.id:d.label,target,type,d.loc));
        }
        Set<String> edges=new HashSet<>();
        for(int i=0;i<links.size();i++){
            var a=links.get(i);Node from=nodes.get(a[0]),to=nodes.get(a[1]);var e=new Edge(from,to,a[2],locations.get(i));
            if(from==to||!edges.add(e.id))throw error("DUPLICATE_OR_SELF_EDGE",e.location,e.id);
            from.out.add(e);to.in.add(e);
        }
        Node start=nodes.get("start"),finish=nodes.get("finish");
        if(start==null||finish==null||!start.in.isEmpty()||start.out.isEmpty()||!finish.out.isEmpty()||finish.in.isEmpty())throw new FlowException("INVALID_ENDPOINTS",id);
        if(nodes.values().stream().noneMatch(n->n.type==Type.TASK||n.type==Type.CALL_FLOW))throw new FlowException("EMPTY_FLOW",id);
        for(var n:nodes.values()){
            if(n.type==Type.XOR_SPLIT||n.type==Type.AND_SPLIT){
                if(n.in.size()>=2&&n.out.size()==1)n.type=n.type==Type.XOR_SPLIT?Type.XOR_JOIN:Type.AND_JOIN;
                else if(n.in.size()!=1||n.out.size()<2)throw error("GATEWAY_DEGREE",n.location,n.id);
            }
            if(n.type==Type.XOR_SPLIT){
                if(n.out.size()>32)throw error("DEFINITION_LIMIT",n.location,"Gateway edge limit");
                long defaults=n.out.stream().filter(Edge::fallback).count();
                if(defaults>1)throw error("MULTIPLE_DEFAULTS",n.location,n.id);
                for(var e:n.out){if(e.text==null)throw error("MISSING_CONDITION",e.location,e.id);if(!e.fallback())e.condition=evaluator.parse(e.text,e.location);}
            } else if(n.out.stream().anyMatch(e->e.text!=null))throw error("CONDITION_NOT_ALLOWED",n.location,n.id);
        }
        Map<Node,Integer> degrees=new IdentityHashMap<>();Deque<Node> ready=new ArrayDeque<>();
        nodes.values().forEach(n->{degrees.put(n,n.in.size());if(n.in.isEmpty())ready.add(n);});
        List<Node> order=new ArrayList<>();
        while(!ready.isEmpty()){var n=ready.remove();order.add(n);for(var e:n.out)if(degrees.compute(e.to,(k,v)->v-1)==0)ready.add(e.to);}
        if(order.size()!=nodes.size())throw new FlowException("GRAPH_CYCLE",id);
        Set<Node> forward=walk(start,false,null),backward=walk(finish,true,null);
        if(forward.size()!=nodes.size()||backward.size()!=nodes.size())throw new FlowException("UNREACHABLE_NODE",id);
        for(var n:order)for(var e:n.in){n.ancestors.addAll(e.from.ancestors);n.ancestors.add(e.from.id);}
        validateExclusiveRegions(order,finish);
        for(var n:nodes.values())if(n.type==Type.TASK){n.bean=resolver.resolve(n.target);if(n.bean==null)throw error("BEAN_NOT_FOUND",n.location,n.target);}
        try {return new Definition(id,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(markdown.getBytes(StandardCharsets.UTF_8))),order.stream().map(n -> new Definition.NodeSpec(n.id,n.label,n.target,n.type,n.location,n.bean,n.ancestors)).toList(),nodes.values().stream().flatMap(n -> n.out.stream()).map(e -> new Definition.EdgeSpec(e.from.id,e.to.id,e.text,e.location,e.condition)).toList());}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    /**
     * 利用后支配集合寻找每个排他分叉最近的公共汇合点。
     * <p>要求分支在配对汇合前互不重叠、无外部入口出口，并只有一个汇合出口；
     * 这是本框架对可执行 DAG 的结构约束，不等同于通用 BPMN token 透传语义。
     */
    private void validateExclusiveRegions(List<Node> order,Node finish){
        Map<Node,Set<Node>> post=new IdentityHashMap<>();
        for(int i=order.size()-1;i>=0;i--){var n=order.get(i);Set<Node> s=new HashSet<>();
            if(!n.out.isEmpty()){s.addAll(post.get(n.out.get(0).to));for(var e:n.out)s.retainAll(post.get(e.to));}s.add(n);post.put(n,s);
        }
        Set<Node> paired=new HashSet<>();
        for(var split:order)if(split.type==Type.XOR_SPLIT){
            Node join=order.stream().filter(n->n!=split&&post.get(split).contains(n)).findFirst().orElse(null);
            if(join==null||join.type!=Type.XOR_JOIN||!paired.add(join))throw error("GATEWAY_STRUCTURE_INVALID",split.location,split.id);
            Set<Node> union=new HashSet<>();
            for(var e:split.out){
                Set<Node> region=walk(e.to,false,join);
                for(var n:region)if(!union.add(n))throw error("GATEWAY_STRUCTURE_INVALID",n.location,"Branches overlap before join");
                for(var n:region){
                    if(n.in.stream().anyMatch(x->x.from!=split&&!region.contains(x.from)))throw error("GATEWAY_STRUCTURE_INVALID",n.location,"External branch entry");
                    if(n.out.stream().anyMatch(x->x.to!=join&&!region.contains(x.to)))throw error("GATEWAY_STRUCTURE_INVALID",n.location,"External branch exit");
                }
                long exits=e.to==join?1:region.stream().flatMap(n->n.out.stream()).filter(x->x.to==join).count();
                if(exits!=1)throw error("GATEWAY_STRUCTURE_INVALID",split.location,"Parallel branch must join before XOR join");
            }
            if(join.in.stream().anyMatch(e->e.from!=split&&!union.contains(e.from)))throw error("GATEWAY_STRUCTURE_INVALID",join.location,"Unrelated join input");
        }
        for(var n:order)if(n.type==Type.XOR_JOIN&&!paired.contains(n))throw error("GATEWAY_STRUCTURE_INVALID",n.location,"Unpaired XOR join");
    }

    /**
     * 沿正向或反向边收集可达节点；到 stop 即停止且不包含 stop。
     */
    private static Set<Node> walk(Node node,boolean reverse,Node stop){
        Set<Node> result=new HashSet<>();Deque<Node> q=new ArrayDeque<>();q.add(node);
        while(!q.isEmpty()){var n=q.remove();if(n==stop||!result.add(n))continue;for(var e:reverse?n.in:n.out)q.add(reverse?e.from:e.to);}return result;
    }

    /**
     * 合并重复节点引用；允许后续补充形状，拒绝互相冲突的显式声明。
     */
    private static void merge(Map<String,Decl> map,Decl d){
        Decl old=map.get(d.id);if(old==null||old.shape==null)map.put(d.id,d);
        else if(d.shape!=null&&(!old.shape.equals(d.shape)||!Objects.equals(old.label,d.label)))throw error("CONFLICTING_NODE",d.loc,d.id);
    }

    /**
     * 将结构化源码位置附入诊断文本，错误码仍单独保留。
     */
    static FlowException error(String code,SourceLocation loc,String text){return new FlowException(code,loc+" "+text);}

    /**
     * 单行 Mermaid 游标，保留 Markdown 行列位置。
     * 只识别约定的节点形状、箭头和双引号标签，不尝试兼容完整 Mermaid 语法。
     */
    private static final class Cursor {
        final String source,line;final int number;int p;
        Cursor(String s,String l,int n){source=s;line=l;number=n;}
        void space(){while(!end()&&Character.isWhitespace(line.charAt(p)))p++;}
        boolean end(){return p>=line.length();}String rest(){return line.substring(p);}
        boolean take(String s){if(line.startsWith(s,p)){p+=s.length();return true;}return false;}
        void expect(String s){if(!take(s))throw error("UNSUPPORTED_SYNTAX","Expected "+s);}

        /**
         * 读取直到给定终止符并消费终止符；不支持标签内转义语法。
         */
        String until(String end){int a=p;while(!this.end()&&!line.startsWith(end,p))p++;if(this.end())throw error("UNCLOSED_LABEL",end);String result=line.substring(a,p);p+=end.length();return result;}
        FlowException error(String c,String s){return FlowCompiler.error(c,new SourceLocation(source,number,p+1),s);}

        /**
         * 解析完整节点 ID 与可选形状；此处保留别名，目标 ID 在后续类型解析时分离。
         */
        Decl node(){space();int a=p;if(end()||!(Character.isLetter(line.charAt(p))||line.charAt(p)=='_'))throw error("INVALID_NODE_ID","Expected node ID");
            while(!end()&&(Character.isLetterOrDigit(line.charAt(p))||line.charAt(p)=='_'))p++;
            String id=line.substring(a,p);if(!id.matches("[A-Za-z_][A-Za-z0-9_]*"))throw error("INVALID_NODE_ID",id);
            SourceLocation loc=new SourceLocation(source,number,a+1);space();String label=null,shape=null;
            if(take("[[\"")){label=until("\"]]");shape="call";}
            else if(take("[\"")){label=until("\"]");shape="task";}
            else if(take("{\"")){label=until("\"}");shape="diamond";}
            else if(take("([")){label=until("])");shape="event";}
            return new Decl(id,label,shape,loc);
        }
    }
}
