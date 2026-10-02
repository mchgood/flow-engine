package io.github.mchgood.flow.consumer;

import io.github.mchgood.flow.api.FlowEngine;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.result.ChildFlowResultView;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在无父 POM 的独立应用中验证已打包 Starter 的 imports、资源加载、Bean 绑定和子流程输出。
 */
class PackagedStarterTest {
    /** 已打包的自动配置从 imports 文件发现，测试不直接导入框架配置类。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(Work.class)
    static class Application {
    }

    /** 模拟用户业务 Bean。 */
    @Component("work")
    static class Work implements FlowNode<String> {
        @Override
        public String execute(NodeContext context) {
            return context.input(String.class) + ":" + context.nodeId();
        }
    }

    @Test
    void packagedStarterLoadsAndExecutesParentAndChild() {
        try (var context = application(true)) {
            FlowEngine engine = context.getBean(FlowEngine.class);
            var result = engine.execute("parent", "request");
            assertTrue(result.succeeded(), result.errors().toString());
            assertFalse(result.results().containsKey("work_inner"));
            var child = (ChildFlowResultView) result.results().get("child_main").value();
            assertEquals("request:work_inner", child.results().get("work_inner").value());
        }
    }

    @Test
    void packagedStarterCanDisableFlowLoadingAndStillExecuteExplicitDefinitions() {
        try (var context = application(false)) {
            FlowEngine engine = context.getBean(FlowEngine.class);
            engine.register("manual", "```mermaid\nflowchart TD\nstart([s]) --> work --> finish([f])\n```");
            assertTrue(engine.execute("manual", "request").succeeded());
        }
    }

    private AnnotationConfigApplicationContext application(boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("smoke", Map.of(
            "flow-engine.flows.locations", "classpath:smoke/flows.md", "flow-engine.flows.enabled", enabled)));
        context.register(Application.class);
        context.refresh();
        return context;
    }
}
