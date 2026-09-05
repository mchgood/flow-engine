package io.github.mchgood.flow;

import io.github.mchgood.flow.api.FlowEngine;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.result.FlowResult;

import io.github.mchgood.flow.runtime.DefaultFlowEngine;
import io.github.mchgood.flow.spring.SpelConditionEvaluator;
import io.github.mchgood.flow.spring.SpringNodeResolver;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import java.util.*;

/**
 * 普通 Spring 的可执行订单示例，展示条件、并行、别名复用和显式子流程。
 * <p>不需要 Boot、Web 或数据库；业务节点只返回演示值，不代表真实订单处理。
 * run 每次创建并关闭独立 Spring 容器，适用于演示和测试，不是生产调用的推荐生命周期。
 */
public final class OrderExample {

    /**
     * 演示用 Spring 配置，节点名称与 Mermaid ID 的目标部分一致。
     */
    @Configuration
    public static class Application {

        /**
         * 返回校验结果及当前别名节点 ID。
         *
         * @return 无执行状态成员的示例任务
         */
        @Bean public FlowNode validateOrder(){return ctx->Map.of("valid",true,"call",ctx.nodeId());}

        /**
         * 返回演示库存预占结果。
         *
         * @return 无执行状态成员的示例任务
         */
        @Bean public FlowNode reserveStock(){return ctx->Map.of("reserved",true);}

        /**
         * 从输入读取 amount 并返回金额。
         *
         * @return 无执行状态成员的示例任务
         */
        @Bean public FlowNode calculatePrice(){return ctx->Map.of("total",ctx.input(Map.class).get("amount"));}

        /**
         * 返回需要人工复核的演示标记，不等待人工操作。
         *
         * @return 无执行状态成员的示例任务
         */
        @Bean public FlowNode recordReview(){return ctx->"needs manual review";}

        /**
         * 返回演示保存结果。
         *
         * @return 无执行状态成员的示例任务
         */
        @Bean public FlowNode saveOrder(){return ctx->Map.of("saved",true);}

        /**
         * 演示非 Boot 应用如何装配并管理引擎关闭。
         *
         * @param beans 宿主容器
         * @return 标准引擎
         */
        @Bean(destroyMethod="close") public FlowEngine flowEngine(ConfigurableListableBeanFactory beans){
            return new DefaultFlowEngine(new SpringNodeResolver(beans),new SpelConditionEvaluator());
        }
    }

    /**
     * 创建容器、原子注册父子流程并执行金额为 800 的订单。
     *
     * @return 本次订单流程结果；返回前容器已经关闭
     */
    public static FlowResult run(){
        try(var spring=new AnnotationConfigApplicationContext(Application.class)){
            var engine=spring.getBean(FlowEngine.class);
            engine.registerAll(Map.of("orderFlow",ORDER,"fulfillment",FULFILLMENT));
            return engine.execute("orderFlow",Map.of("amount",800));
        }
    }

    /**
     * 运行示例并输出流程及节点状态。
     *
     * @param args 命令行参数，本示例不使用
     */
    public static void main(String[] args){var result=run();System.out.println("status="+result.status()+", execution="+result.executionId());result.results().forEach((id,n)->System.out.println(id+": "+n.status()));}

    /**
     * 订单父流程：按金额选择履约子流程或复核记录，前后两次复用校验 Bean。
     */
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

    /**
     * 履约子流程：并行库存与计价，汇合后保存订单。
     */
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
