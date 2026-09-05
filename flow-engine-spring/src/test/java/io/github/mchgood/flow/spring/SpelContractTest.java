package io.github.mchgood.flow.spring;

import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.result.*;
import io.github.mchgood.flow.spi.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 条件白名单、严格类型、访问错误及同一编译表达式并发复用测试。 */
class SpelContractTest {
    private final SpelConditionEvaluator evaluator=new SpelConditionEvaluator();
    private final SourceLocation location=new SourceLocation("flow",4,5);
    private NodeContext context(Object input) {return new NodeContext("e","f","gate",input,Map.of());}
    @ParameterizedTest @ValueSource(strings={"#input.amount == 10","#input['amount'] >= 10 and #input.amount < 11","#input.amount + 2 == 12","#input.amount % 3 == 1","!(#input.amount < 0)","#input.amount == 10 ? true : false","(#input['absent'] ?: 7) == 7","#input.amount / 2 == 5"})
    void allowedExpressionsRemainUsable(String source) {
        assertTrue(evaluator.evaluate(evaluator.parse(source,location),context(Map.of("amount",10))));
    }
    @ParameterizedTest @ValueSource(strings={"#input.remove('amount')","#input.amount--","#input.amount = 2","#input.classLoader","#input.declaringClass","#this","#input.?[true]","#input.![true]","#results['a' + 'b'].present"})
    void rejectedOperationsHaveStableErrorCode(String source) {
        assertEquals("EXPRESSION_FORBIDDEN",assertThrows(FlowException.class,()->evaluator.parse(source,location)).code());
    }
    @ParameterizedTest @ValueSource(strings={"null","42","0.1","'false'"})
    void rejectsTruthyCoercion(String source) {
        var parsed=evaluator.parse(source,location);
        assertEquals("EXPRESSION_TYPE_ERROR",assertThrows(FlowException.class,()->evaluator.evaluate(parsed,context(null))).code());
    }
    @Test void missingAncestorRetainsAccessDeniedCode() {
        var parsed=evaluator.parse("#results['missing'].present",location);
        assertEquals("CONTEXT_ACCESS_DENIED",assertThrows(FlowException.class,()->evaluator.evaluate(parsed,context(null))).code());
    }
    @Test void malformedAndOversizedExpressionsAreDistinguished() {
        assertEquals("EXPRESSION_SYNTAX_ERROR",assertThrows(FlowException.class,()->evaluator.parse("#input[",location)).code());
        assertEquals("EXPRESSION_LIMIT",assertThrows(FlowException.class,()->evaluator.parse("x".repeat(2049),location)).code());
        assertEquals("EXPRESSION_LIMIT",assertThrows(FlowException.class,()->evaluator.parse(null,location)).code());
        assertEquals("EXPRESSION_FORBIDDEN",assertThrows(FlowException.class,()->evaluator.parse("!".repeat(34)+"true",location)).code());
    }
    @Test void oneCompiledConditionCanBeEvaluatedConcurrentlyWithoutContextLeakage() throws Exception {
        var parsed=evaluator.parse("#input.amount == 10",location);var callers=Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<100;i++){int value=i;results.add(callers.submit(()->evaluator.evaluate(parsed,context(Map.of("amount",value)))));}
            for(int i=0;i<results.size();i++)assertEquals(i==10,results.get(i).get(3,TimeUnit.SECONDS));
        } finally {callers.shutdownNow();}
    }
    @Test void shortCircuitDoesNotReadInaccessibleAncestor() {
        assertFalse(evaluator.evaluate(evaluator.parse("false and #results['missing'].present",location),context(null)));
    }
    @Test void skippedAncestorCanBeInspectedWithoutConfusingSuccessfulNull() {
        var skipped=new NodeRecord("a","a","TASK",NodeStatus.SKIPPED,false,null,"BRANCH_NOT_SELECTED",null,null,null,null);
        var success=new NodeRecord("b","b","TASK",NodeStatus.SUCCEEDED,true,null,null,null,null,null,null);
        var ctx=new NodeContext("e","f","gate",null,Map.of("a",skipped,"b",success));
        assertTrue(evaluator.evaluate(evaluator.parse("!#results['a'].present and #results['b'].present and #results['b'].value == null",location),ctx));
    }
}
