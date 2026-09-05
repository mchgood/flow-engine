package io.github.mchgood.flow.contract;

import io.github.mchgood.flow.api.ExecutionOptions;
import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.result.*;
import io.github.mchgood.flow.exception.FlowException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 配置边界和上下文/结果的只读、空值及类型契约测试。 */
class ValueContractTest {
    static Stream<Arguments> invalidCapacities() {
        return java.util.stream.IntStream.range(0,7).boxed().flatMap(i -> Stream.of(-1,0).filter(v -> i!=4 || v!=0).map(v -> Arguments.of(i,v)));
    }
    @ParameterizedTest @MethodSource("invalidCapacities")
    void eachResourceLimitIsValidated(int index,int value) {
        int[] limits={1,1,1,1,1,1,1};limits[index]=value;
        assertThrows(IllegalArgumentException.class,()->new EngineConfig(limits[0],limits[1],limits[2],limits[3],limits[4],limits[5],limits[6],Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1)));
    }
    static Stream<Arguments> invalidDurations() {
        return java.util.stream.IntStream.range(0,4).boxed().flatMap(i -> Stream.of(null,Duration.ZERO,Duration.ofNanos(-1),Duration.ofDays(1).plusNanos(1)).map(d->Arguments.of(i,d)));
    }
    @ParameterizedTest @MethodSource("invalidDurations")
    void eachDeadlineIsValidated(int index,Duration value) {
        Duration[] d={Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1)};d[index]=value;
        assertThrows(IllegalArgumentException.class,()->new EngineConfig(1,1,1,1,0,1,1,d[0],d[1],d[2],d[3]));
    }
    @Test void executionOptionBoundaries() {
        assertNull(ExecutionOptions.defaults().timeout());
        assertEquals(Duration.ofNanos(1),ExecutionOptions.withTimeout(Duration.ofNanos(1)).timeout());
        assertDoesNotThrow(()->ExecutionOptions.withTimeout(Duration.ofDays(1)));
        for(var d:List.of(Duration.ZERO,Duration.ofNanos(-1),Duration.ofDays(1).plusNanos(1)))assertThrows(IllegalArgumentException.class,()->ExecutionOptions.withTimeout(d));
    }
    @Test void allowedDepthEndpointsAndUpperBound() {
        for(int depth:List.of(0,32))assertDoesNotThrow(()->new EngineConfig(1,1,1,1,depth,1,1,Duration.ofDays(1),Duration.ofNanos(1),Duration.ofDays(1),Duration.ofNanos(1)));
        assertThrows(IllegalArgumentException.class,()->new EngineConfig(1,1,1,1,33,1,1,Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1)));
    }
    private NodeRecord record(String id,NodeStatus status,Object value) {
        return new NodeRecord(id,id,"TASK",status,status==NodeStatus.SUCCEEDED,value,status==NodeStatus.SKIPPED?"BRANCH_NOT_SELECTED":null,null,null,Instant.now(),null);
    }
    @Test void contextCopiesMapButDoesNotDeepCopyBusinessObjects() {
        List<String> value=new ArrayList<>(List.of("one"));Map<String,NodeRecord> map=new HashMap<>();map.put("first",record("first",NodeStatus.SUCCEEDED,value));
        var context=new NodeContext("execution","flow","second",value,map);map.clear();
        assertSame(value,context.input());assertSame(value,context.ancestorValue("first",List.class));
        assertThrows(UnsupportedOperationException.class,()->context.ancestors().clear());
        assertThrows(ClassCastException.class,()->context.input(Integer.class));
        assertThrows(ClassCastException.class,()->context.ancestorValue("first",Integer.class));
    }
    @Test void absentSkippedAndSuccessfulNullAreDistinct() {
        var context=new NodeContext("e","f","n",null,Map.of("nullTask",record("nullTask",NodeStatus.SUCCEEDED,null),"skipped",record("skipped",NodeStatus.SKIPPED,null)));
        assertNull(context.input(String.class));assertTrue(context.ancestorOutput("nullTask").present());assertNull(context.ancestorValue("nullTask",String.class));
        assertFalse(context.ancestorOutput("skipped").present());
        assertEquals("MISSING_NODE_OUTPUT",assertThrows(FlowException.class,()->context.ancestorValue("skipped",Object.class)).code());
        assertEquals("CONTEXT_ACCESS_DENIED",assertThrows(FlowException.class,()->context.ancestorStatus("other")).code());
    }
    @Test void resultCollectionsAreDefensiveCopies() {
        Map<String,NodeRecord> nodes=new HashMap<>();nodes.put("one",record("one",NodeStatus.SUCCEEDED,null));
        var errors=new ArrayList<FlowError>();var unfinished=new ArrayList<String>();unfinished.add("path");
        var result=new FlowResult("e","e",null,"f","hash",FlowStatus.FAILED,Instant.now(),Instant.now(),nodes,errors,unfinished);
        nodes.clear();errors.add(new FlowError("later","",null,null,null));unfinished.clear();
        assertEquals(1,result.results().size());assertTrue(result.errors().isEmpty());assertEquals(List.of("path"),result.physicalExitUnconfirmed());
        assertThrows(UnsupportedOperationException.class,()->result.results().clear());assertThrows(UnsupportedOperationException.class,()->result.errors().clear());assertThrows(UnsupportedOperationException.class,()->result.physicalExitUnconfirmed().clear());
    }
}
