package io.github.mchgood.flow.spring;

import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.result.NodeStatus;
import io.github.mchgood.flow.runtime.DefaultFlowEngine;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 验证不同返回类型的节点通过 Spring 容器及代理后仍能编排和按类型读取。 */
class GenericNodeIntegrationTest {
    /** 示例业务输出，不包含引擎执行状态。 */
    record ValidationResult(boolean valid) {}

    /** 具体泛型 Bean 返回值参与容器类型推断；工作引擎仅持有通配符引用。 */
    @Configuration(proxyBeanMethods=false)
    static class Nodes {
        @Bean FlowNode<ValidationResult> validate() {return context->new ValidationResult(true);}
        @Bean FlowNode<String> describe() {return context->context.ancestorValue("validate",ValidationResult.class).valid()?"approved":"rejected";}
        @Bean FlowNode<Void> complete() {return context->null;}
        @Bean FlowNode<Integer> wrongType() {return context->context.ancestorValue("validate",Integer.class);}
    }

    private static String graph(String tasks) {
        return "```mermaid\nflowchart TD\nstart([s]) --> "+tasks+" --> finish([f])\n```";
    }

    @Test void heterogeneousOutputsAndVoidArePreserved() {
        try(var spring=new AnnotationConfigApplicationContext(Nodes.class);
            var engine=new DefaultFlowEngine(new SpringNodeResolver(spring.getBeanFactory()),new SpelConditionEvaluator())) {
            engine.register("typed",graph("validate --> describe --> complete"));
            var result=engine.execute("typed",null);
            assertTrue(result.succeeded(),result.errors().toString());
            assertEquals(new ValidationResult(true),result.results().get("validate").value());
            assertEquals("approved",result.results().get("describe").value());
            assertTrue(result.results().get("complete").present());
            assertNull(result.results().get("complete").value());
        }
    }

    @Test void incompatibleDownstreamReadStillFailsAtRuntime() {
        try(var spring=new AnnotationConfigApplicationContext(Nodes.class);
            var engine=new DefaultFlowEngine(new SpringNodeResolver(spring.getBeanFactory()),new SpelConditionEvaluator())) {
            engine.register("typed",graph("validate --> wrongType --> complete"));
            var result=engine.execute("typed",null);
            assertFalse(result.succeeded());
            assertEquals("NODE_FAILED",result.results().get("wrongType").error().code());
            assertEquals(NodeStatus.SKIPPED,result.results().get("complete").status());
        }
    }

    @Test void genericNodeInvokesTheContainerProxy() {
        var intercepted=new AtomicInteger();
        FlowNode<ValidationResult> node=context->new ValidationResult(true);
        var proxy=new ProxyFactory(node);
        proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation->{intercepted.incrementAndGet();return invocation.proceed();});
        try(var spring=new AnnotationConfigApplicationContext()) {
            spring.getBeanFactory().registerSingleton("validate",proxy.getProxy());spring.refresh();
            var resolver=new SpringNodeResolver(spring.getBeanFactory());
            assertSame(spring.getBean("validate"),resolver.resolve("validate"));
            try(var engine=new DefaultFlowEngine(resolver,new SpelConditionEvaluator())) {
                engine.register("typed",graph("validate"));
                var result=engine.execute("typed",null);
                assertTrue(result.succeeded());
                assertEquals(new ValidationResult(true),result.results().get("validate").value());
                assertEquals(1,intercepted.get());
            }
        }
    }
}
