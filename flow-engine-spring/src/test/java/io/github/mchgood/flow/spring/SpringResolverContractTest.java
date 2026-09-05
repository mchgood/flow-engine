package io.github.mchgood.flow.spring;

import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.FlowNode;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.annotation.*;
import static org.junit.jupiter.api.Assertions.*;

/** 容器解析失败、别名和作用域代理边界测试。 */
class SpringResolverContractTest {
    @Test void missingAndWrongTypeBeansAreBindingErrors() {
        try(var ctx=new GenericApplicationContext()) {
            ctx.registerBean("wrong",String.class,()->"wrong");ctx.refresh();
            var resolver=new SpringNodeResolver(ctx.getBeanFactory());
            for(var id:new String[]{"missing","wrong"})assertEquals("BEAN_BINDING_ERROR",assertThrows(FlowException.class,()->resolver.resolve(id)).code());
        }
    }
    @Test void containerBeanAliasPreservesIdentity() {
        try(var ctx=new GenericApplicationContext()) {
            ctx.registerBean("work",FlowNode.class,()->c->1);ctx.registerAlias("work","alias");ctx.refresh();
            var resolver=new SpringNodeResolver(ctx.getBeanFactory());assertSame(resolver.resolve("work"),resolver.resolve("alias"));
        }
    }
    @Test void singletonProxyOverPrototypeTargetIsRejected() {
        try(var ctx=new AnnotationConfigApplicationContext(PrototypeProxy.class)) {
            assertTrue(ctx.getBeanFactory().isSingleton("work"));
            assertEquals("BEAN_SCOPE_UNSUPPORTED",assertThrows(FlowException.class,()->new SpringNodeResolver(ctx.getBeanFactory()).resolve("work")).code());
        }
    }
    /** singleton 代理外观不能掩盖 prototype 目标。 */
    @Configuration(proxyBeanMethods=false)
    static class PrototypeProxy {
        @Bean @Scope(value="prototype",proxyMode=ScopedProxyMode.INTERFACES)
        FlowNode<?> work() {return context->1;}
    }
}
