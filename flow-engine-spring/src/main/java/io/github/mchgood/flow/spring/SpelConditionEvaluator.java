package io.github.mchgood.flow.spring;

import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.spi.CompiledCondition;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.SourceLocation;


import org.springframework.expression.*;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.standard.*;
import org.springframework.expression.spel.support.*;
import java.util.*;

/**
 * 面向宿主提供的流程定义的受限、只读 SpEL 适配器。
 * <p>注册期通过 AST 白名单拒绝方法、构造器、类型、Bean 引用和赋值；运行期只提供
 * #input 与 #results，并要求原始结果为 Boolean，不接受字符串 true 等隐式转换。
 * 每次求值创建独立上下文，编译条件跨执行复用。
 * <p>这不是可执行任意不可信代码的沙箱：只读属性访问仍可能调用业务 getter，
 * 输入与结果对象也不深拷贝；宿主必须保证暴露对象的 getter 无副作用且线程安全。
 */
public final class SpelConditionEvaluator implements ConditionEvaluator {
    private static final Set<String> ALLOWED=Set.of("CompoundExpression","VariableReference","PropertyOrFieldReference","Indexer",
        "StringLiteral","BooleanLiteral","NullLiteral","IntLiteral","LongLiteral","RealLiteral","FloatLiteral",
        "OpAnd","OpOr","OperatorNot","OpEQ","OpNE","OpLT","OpLE","OpGT","OpGE",
        "OpPlus","OpMinus","OpMultiply","OpDivide","OpModulus","Ternary","Elvis");

    /**
     * 本求值器私有的条件句柄，保存表达式与诊断位置，不保存执行上下文。
     */
    private record Parsed(SpelExpression expression,SourceLocation location) implements CompiledCondition{}

    /**
     * {@inheritDoc}
     * <p>表达式最多 2048 字符、AST 深度最多 32；语法错误与禁止操作使用不同错误码。
     */
    @Override public CompiledCondition parse(String text,SourceLocation location){
        if(text==null||text.length()>2048)throw new FlowException("EXPRESSION_LIMIT",location.toString());
        try {
            var expression=(SpelExpression)new SpelExpressionParser().parseExpression(text);
            validate(expression.getAST(),0);
            return new Parsed(expression,location);
        }catch(FlowException e){throw e;}catch(RuntimeException e){throw new FlowException("EXPRESSION_SYNTAX_ERROR",location+" Invalid SpEL",e);}
    }

    /**
     * 递归验证 AST 种类与变量访问；#results 必须使用字面量节点 ID 索引。
     * 祖先是否实际可见仍需在运行时检查，不能只靠语法白名单判断。
     */
    private void validate(SpelNode node,int depth){
        String kind=node.getClass().getSimpleName();
        if(depth>32||!ALLOWED.contains(kind))throw new FlowException("EXPRESSION_FORBIDDEN",kind);
        if(kind.equals("VariableReference")&&!Set.of("#input","#results").contains(node.toStringAST()))throw new FlowException("EXPRESSION_FORBIDDEN",node.toStringAST());
        if(kind.equals("PropertyOrFieldReference")&&Set.of("class","classLoader","declaringClass").contains(node.toStringAST()))throw new FlowException("EXPRESSION_FORBIDDEN",node.toStringAST());
        if(kind.equals("CompoundExpression")&&node.getChildCount()>1&&node.getChild(0).toStringAST().equals("#results")){
            var index=node.getChild(1);
            if(!index.getClass().getSimpleName().equals("Indexer")||index.getChildCount()!=1||!index.getChild(0).getClass().getSimpleName().equals("StringLiteral"))throw new FlowException("EXPRESSION_FORBIDDEN","Use a literal ancestor ID");
        }
        for(int i=0;i<node.getChildCount();i++)validate(node.getChild(i),depth+1);
    }

    /**
     * {@inheritDoc}
     * <p>只接受本实现 parse 返回的句柄。为每次调用创建祖先输出视图和禁止写入的求值上下文。
     */
    @Override public boolean evaluate(CompiledCondition condition,NodeContext data){
        var parsed=(Parsed)condition;
        try{
            Map<String,Object> results=new LinkedHashMap<>();
            data.ancestors().forEach((id,n)->{
                Map<String,Object> out=new LinkedHashMap<>();out.put("status",n.status().name());out.put("present",n.present());out.put("value",n.value());out.put("skipReason",n.skipReason());
                results.put(id,Collections.unmodifiableMap(out));
            });
            Map<String,Object> guarded=new AbstractMap<>(){
                public Set<Entry<String,Object>> entrySet(){return Collections.unmodifiableMap(results).entrySet();}
                @Override public Object get(Object id){if(!results.containsKey(id))throw new FlowException("CONTEXT_ACCESS_DENIED","Not an ancestor: "+id);return results.get(id);}
            };
            var context=SimpleEvaluationContext.forPropertyAccessors(new ReadOnlyMapAccessor(),DataBindingPropertyAccessor.forReadOnlyAccess()).withAssignmentDisabled().build();
            context.setVariable("input",data.input());context.setVariable("results",guarded);
            Object value=parsed.expression.getValue(context);
            if(!(value instanceof Boolean result))throw new FlowException("EXPRESSION_TYPE_ERROR",parsed.location+" Expected Boolean");
            return result;
        }catch(FlowException e){throw e;}catch(RuntimeException e){throw new FlowException("EXPRESSION_EVALUATION_ERROR",parsed.location+" SpEL evaluation failed",e);}
    }

    /**
     * 只读 Map 属性适配器，支持 input.amount 等写法，显式拒绝写入。
     */
    private static final class ReadOnlyMapAccessor implements PropertyAccessor {
        public Class<?>[] getSpecificTargetClasses(){return new Class<?>[]{Map.class};}
        public boolean canRead(EvaluationContext c,Object target,String name){return target instanceof Map<?,?> map&&map.containsKey(name);}
        public TypedValue read(EvaluationContext c,Object target,String name){return new TypedValue(((Map<?,?>)target).get(name));}
        public boolean canWrite(EvaluationContext c,Object target,String name){return false;}
        public void write(EvaluationContext c,Object target,String name,Object value){throw new FlowException("EXPRESSION_FORBIDDEN","Read only");}
    }
}
