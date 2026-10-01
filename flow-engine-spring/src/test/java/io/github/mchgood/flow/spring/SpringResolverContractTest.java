package io.github.mchgood.flow.spring;

import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.node.NodeContext;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.stereotype.Component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 容器解析失败、别名和作用域代理边界测试。 */
class SpringResolverContractTest {
    @Test
    void missingAndWrongTypeBeansAreBindingErrors() {
        try (var ctx = new GenericApplicationContext()) {
            ctx.registerBean("wrong", String.class, () -> "wrong");
            ctx.refresh();
            var resolver = new SpringNodeResolver(ctx.getBeanFactory());
            for (var id : new String[] {"missing", "wrong"}) {
                assertEquals("BEAN_BINDING_ERROR",
                        assertThrows(FlowException.class, () -> resolver.resolve(id)).code());
            }
        }
    }

    @Test
    void containerBeanAliasPreservesIdentity() {
        try (var ctx = new GenericApplicationContext()) {
            ctx.registerBean("work", FlowNode.class, () -> context -> 1);
            ctx.registerAlias("work", "alias");
            ctx.refresh();
            var resolver = new SpringNodeResolver(ctx.getBeanFactory());
            assertSame(resolver.resolve("work"), resolver.resolve("alias"));
        }
    }

    @Test
    void singletonProxyOverPrototypeTargetIsRejected() {
        try (var ctx = new AnnotationConfigApplicationContext(PrototypeProxy.class)) {
            assertTrue(ctx.getBeanFactory().isSingleton("work"));
            assertEquals("BEAN_SCOPE_UNSUPPORTED", assertThrows(FlowException.class,
                    () -> new SpringNodeResolver(ctx.getBeanFactory()).resolve("work")).code());
        }
    }

    /** singleton 代理外观不能掩盖 prototype 目标。 */
    @Configuration(proxyBeanMethods = false)
    static class PrototypeProxy {
        /** prototype 作用域与接口代理外观注册的 work 节点，用于暴露代理背后的非 singleton 目标。 */
        @Component("work")
        @Scope(value = "prototype", proxyMode = ScopedProxyMode.INTERFACES)
        static class WorkNode implements FlowNode<Integer> {
            @Override
            public Integer execute(NodeContext context) {
                return 1;
            }
        }
    }
}
