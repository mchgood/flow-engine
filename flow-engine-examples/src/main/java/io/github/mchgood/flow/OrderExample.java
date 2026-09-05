package io.github.mchgood.flow;

import io.github.mchgood.flow.engine.DefaultFlowEngine;
import io.github.mchgood.flow.spring.SpelConditionEvaluator;
import io.github.mchgood.flow.spring.SpringNodeResolver;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import java.util.*;

/** Run as a Java main class; no web server, database or Spring Boot required. */
public final class OrderExample {
    @Configuration
    public static class Application {
        @Bean public FlowNode validateOrder(){return ctx->Map.of("valid",true,"call",ctx.nodeId());}
        @Bean public FlowNode reserveStock(){return ctx->Map.of("reserved",true);}
        @Bean public FlowNode calculatePrice(){return ctx->Map.of("total",ctx.input(Map.class).get("amount"));}
        @Bean public FlowNode recordReview(){return ctx->"needs manual review";}
        @Bean public FlowNode saveOrder(){return ctx->Map.of("saved",true);}
        @Bean(destroyMethod="close") public FlowEngine flowEngine(ConfigurableListableBeanFactory beans){
            return new DefaultFlowEngine(new SpringNodeResolver(beans),new SpelConditionEvaluator());
        }
    }
    public static FlowResult run(){
        try(var spring=new AnnotationConfigApplicationContext(Application.class)){
            var engine=spring.getBean(FlowEngine.class);
            engine.registerAll(Map.of("orderFlow",ORDER,"fulfillment",FULFILLMENT));
            return engine.execute("orderFlow",Map.of("amount",800));
        }
    }
    public static void main(String[] args){var result=run();System.out.println("status="+result.status()+", execution="+result.executionId());result.results().forEach((id,n)->System.out.println(id+": "+n.status()));}
    public static final String ORDER="""
        # Order
        ```mermaid
        flowchart TD
            start([开始]) --> validateOrder_before["前置校验"]
            validateOrder_before --> decision{"自动处理？"}
            decision -->|"#input.amount <= 1000"| fulfillment_main[["履约子流程"]]
            decision -->|"default"| recordReview["记录人工处理"]
            fulfillment_main --> merge{"X"}
            recordReview --> merge
            merge --> validateOrder_after["后置校验"]
            validateOrder_after --> finish([结束])
        ```
        """;
    public static final String FULFILLMENT="""
        # Fulfillment
        ```mermaid
        flowchart TD
            start([开始]) --> fork{"+"}
            fork --> reserveStock["预占库存"]
            fork --> calculatePrice["计算价格"]
            reserveStock --> join{"+"}
            calculatePrice --> join
            join --> saveOrder["保存订单"]
            saveOrder --> finish([结束])
        ```
        """;
}
