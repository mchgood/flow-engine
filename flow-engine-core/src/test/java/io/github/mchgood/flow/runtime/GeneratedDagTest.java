package io.github.mchgood.flow.runtime;

import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.spi.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicIntegerArray;
import static org.junit.jupiter.api.Assertions.*;

/** 固定种子的 DAG 生成测试；用独立串行求和模型验证依赖输出和恰好一次执行。 */
@Timeout(15)
class GeneratedDagTest {
    @ParameterizedTest @ValueSource(longs={7,19,83})
    void randomDependenciesMatchSequentialOracle(long seed) {
        Random random=new Random(seed);
        ConditionEvaluator unused=new ConditionEvaluator() {
            public CompiledCondition parse(String text,SourceLocation location){throw new AssertionError("No conditions");}
            public boolean evaluate(CompiledCondition condition,NodeContext context){throw new AssertionError("No conditions");}
        };
        for(int scenario=0;scenario<12;scenario++) {
            int count=8+random.nextInt(13);List<List<Integer>> predecessors=new ArrayList<>();int[] expected=new int[count];
            List<String> edges=new ArrayList<>();boolean[] hasSuccessor=new boolean[count];
            for(int i=0;i<count;i++) {
                List<Integer> parents=new ArrayList<>();
                for(int j=0;j<i;j++)if(random.nextDouble()<0.25)parents.add(j);
                if(i>0&&parents.isEmpty())parents.add(random.nextInt(i));
                predecessors.add(List.copyOf(parents));int value=1;
                for(int parent:parents){value+=expected[parent];edges.add("work_"+parent+" --> work_"+i);hasSuccessor[parent]=true;}
                expected[i]=value;
            }
            edges.add("start([s]) --> work_0");
            for(int i=0;i<count;i++)if(!hasSuccessor[i])edges.add("work_"+i+" --> finish([f])");
            Collections.shuffle(edges,random);
            var calls=new AtomicIntegerArray(count);
            var config=new EngineConfig(4,64,4,4,0,1,1,Duration.ofSeconds(3),Duration.ofSeconds(3),Duration.ofSeconds(6),Duration.ofMillis(50));
            try(var engine=new DefaultFlowEngine(id->context->{
                int index=Integer.parseInt(context.nodeId().substring("work_".length()));calls.incrementAndGet(index);
                int output=1;for(int parent:predecessors.get(index))output+=context.ancestorValue("work_"+parent,Integer.class);
                Thread.yield();return output;
            },unused,config)) {
                engine.register("generated","```mermaid\nflowchart TD\n"+String.join("\n",edges)+"\n```");
                var result=engine.execute("generated",null);assertTrue(result.succeeded(),"seed="+seed+", case="+scenario+": "+result.errors());
                assertEquals(count+2,result.results().size());
                for(int i=0;i<count;i++){assertEquals(1,calls.get(i),"work_"+i);assertEquals(expected[i],result.results().get("work_"+i).value(),"seed="+seed+", work_"+i);}
            }
        }
    }
}
