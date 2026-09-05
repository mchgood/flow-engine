package io.github.mchgood.flow;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.parser.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static io.github.mchgood.flow.Definition.*;

/** Compiles a deliberately small Mermaid subset, preserving source positions. */
final class FlowCompiler {
    private final NodeResolver resolver;
    private final ConditionEvaluator evaluator;
    FlowCompiler(NodeResolver resolver,ConditionEvaluator evaluator){this.resolver=resolver;this.evaluator=evaluator;}
    private record Decl(String id,String label,String shape,SourceLocation loc){}
    Definition compile(String id,String markdown){
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
        try {return new Definition(id,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(markdown.getBytes(StandardCharsets.UTF_8))),nodes,order);}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
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
    private static Set<Node> walk(Node node,boolean reverse,Node stop){
        Set<Node> result=new HashSet<>();Deque<Node> q=new ArrayDeque<>();q.add(node);
        while(!q.isEmpty()){var n=q.remove();if(n==stop||!result.add(n))continue;for(var e:reverse?n.in:n.out)q.add(reverse?e.from:e.to);}return result;
    }
    private static void merge(Map<String,Decl> map,Decl d){
        Decl old=map.get(d.id);if(old==null||old.shape==null)map.put(d.id,d);
        else if(d.shape!=null&&(!old.shape.equals(d.shape)||!Objects.equals(old.label,d.label)))throw error("CONFLICTING_NODE",d.loc,d.id);
    }
    static FlowException error(String code,SourceLocation loc,String text){return new FlowException(code,loc+" "+text);}
    private static final class Cursor {
        final String source,line;final int number;int p;
        Cursor(String s,String l,int n){source=s;line=l;number=n;}
        void space(){while(!end()&&Character.isWhitespace(line.charAt(p)))p++;}
        boolean end(){return p>=line.length();}String rest(){return line.substring(p);}
        boolean take(String s){if(line.startsWith(s,p)){p+=s.length();return true;}return false;}
        void expect(String s){if(!take(s))throw error("UNSUPPORTED_SYNTAX","Expected "+s);}
        String until(String end){int a=p;while(!this.end()&&!line.startsWith(end,p))p++;if(this.end())throw error("UNCLOSED_LABEL",end);String result=line.substring(a,p);p+=end.length();return result;}
        FlowException error(String c,String s){return FlowCompiler.error(c,new SourceLocation(source,number,p+1),s);}
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
