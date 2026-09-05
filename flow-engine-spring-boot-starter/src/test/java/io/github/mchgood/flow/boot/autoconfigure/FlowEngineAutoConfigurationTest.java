package io.github.mchgood.flow.boot.autoconfigure;

import io.github.mchgood.flow.api.FlowEngine;
import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.runtime.DefaultFlowEngine;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.NodeResolver;
import io.github.mchgood.flow.spring.SpelConditionEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 Starter 默认装配、参数校验、用户覆盖、发现入口及关闭语义。
 */
class FlowEngineAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FlowEngineAutoConfiguration.class));
    private static final String FLOW = """
        ```mermaid
        flowchart TD
            start([开始]) --> echo["回显"]
            echo --> finish([结束])
        ```
        """;

    @Test
    void defaultBeansExecuteAnApplicationNode() {
        runner.withBean("echo", FlowNode.class, () -> context -> context.input()).run(context -> {
            assertThat(context).hasSingleBean(FlowEngine.class).hasSingleBean(NodeResolver.class)
                .hasSingleBean(ConditionEvaluator.class).hasSingleBean(EngineConfig.class);
            assertThat(context.getBean(EngineConfig.class)).isEqualTo(EngineConfig.defaults());
            FlowEngine engine = context.getBean(FlowEngine.class);
            engine.register("echoFlow", FLOW);
            var result = engine.execute("echoFlow", "hello");
            assertThat(result.succeeded()).isTrue();
            assertThat(result.results().get("echo").value()).isEqualTo("hello");
        });
    }

    @Test
    void bindsAllResourceProperties() {
        runner.withPropertyValues("flow-engine.worker-threads=2", "flow-engine.queue-capacity=16",
            "flow-engine.max-concurrent-executions=4", "flow-engine.max-in-flight-per-execution=2",
            "flow-engine.max-subflow-depth=3", "flow-engine.max-executions-per-root=9",
            "flow-engine.max-active-children=2", "flow-engine.node-timeout=250ms",
            "flow-engine.gateway-timeout=100ms", "flow-engine.flow-timeout=5s",
            "flow-engine.close-timeout=1s").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(EngineConfig.class)).isEqualTo(new EngineConfig(2,16,4,2,3,9,2,
                    Duration.ofMillis(250),Duration.ofMillis(100),Duration.ofSeconds(5),Duration.ofSeconds(1)));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker-threads=0", "queue-capacity=-1", "node-timeout=0s",
        "flow-timeout=2d", "gateway-timeout=not-a-duration", "max-subflow-depth=33", "worker-threadz=2"})
    void invalidPropertiesFailStartup(String property) {
        runner.withPropertyValues("flow-engine." + property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void disabledDoesNotCreateInfrastructure() {
        runner.withPropertyValues("flow-engine.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(FlowEngine.class).doesNotHaveBean(NodeResolver.class)
                .doesNotHaveBean(ConditionEvaluator.class).doesNotHaveBean(EngineConfig.class);
        });
    }

    @Test
    void customResolverIsUsed() {
        NodeResolver resolver = name -> node -> "custom";
        runner.withBean(NodeResolver.class, () -> resolver).run(context -> {
            assertThat(context).hasSingleBean(NodeResolver.class);
            assertThat(context.getBean(NodeResolver.class)).isSameAs(resolver);
            var engine = context.getBean(FlowEngine.class);
            engine.register("custom", FLOW);
            assertThat(engine.execute("custom", null).results().get("echo").value()).isEqualTo("custom");
        });
    }

    @Test
    void customEvaluatorIsPreserved() {
        ConditionEvaluator evaluator = new SpelConditionEvaluator();
        runner.withBean(ConditionEvaluator.class, () -> evaluator).run(context -> {
            assertThat(context).hasSingleBean(ConditionEvaluator.class);
            assertThat(context.getBean(ConditionEvaluator.class)).isSameAs(evaluator);
            assertThat(context).doesNotHaveBean("flowConditionEvaluator");
        });
    }

    @Test
    void customEngineConfigTakesPrecedence() {
        var config = EngineConfig.defaults();
        runner.withBean(EngineConfig.class, () -> config).withPropertyValues("flow-engine.worker-threads=2")
            .run(context -> {
                assertThat(context).hasSingleBean(EngineConfig.class);
                assertThat(context.getBean(EngineConfig.class)).isSameAs(config);
            });
    }

    @Test
    void customEngineIsNotDuplicated() {
        var engine = new DefaultFlowEngine(id -> node -> null, new SpelConditionEvaluator());
        try {
            runner.withBean("customEngine", FlowEngine.class, () -> engine).run(context -> {
                assertThat(context).hasSingleBean(FlowEngine.class);
                assertThat(context.getBean(FlowEngine.class)).isSameAs(engine);
                assertThat(context).doesNotHaveBean("flowEngine");
            });
        } finally { engine.close(); }
    }

    @Test
    void contextCloseClosesTheEngine() {
        AtomicReference<FlowEngine> saved = new AtomicReference<>();
        runner.run(context -> saved.set(context.getBean(FlowEngine.class)));
        var failure = assertThrows(FlowException.class, () -> saved.get().execute("anything", null));
        assertThat(failure.code()).isEqualTo("ENGINE_CLOSED");
    }

    @Test
    void missingCoreClassesBackOff() {
        runner.withClassLoader(new FilteredClassLoader(FlowEngine.class))
            .run(context -> assertThat(context).doesNotHaveBean(FlowEngineAutoConfiguration.class));
    }

    @Test
    void bootDiscoversConfigurationWithoutAnExplicitImport() {
        new ApplicationContextRunner().withUserConfiguration(BootHost.class)
            .run(context -> assertThat(context).hasSingleBean(FlowEngine.class));
    }

    @Test
    void publishesIdeConfigurationMetadata() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("META-INF/spring-configuration-metadata.json")) {
            assertThat(stream).isNotNull();
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(metadata).contains("flow-engine.worker-threads", "flow-engine.enabled", "flow-engine.node-timeout");
        }
    }

    @Test
    void disabledAutoConfigurationPreservesUserEngine() {
        var engine = new DefaultFlowEngine(id -> node -> null, new SpelConditionEvaluator());
        try {
            runner.withPropertyValues("flow-engine.enabled=false")
                .withBean("custom", FlowEngine.class, () -> engine).run(context -> {
                    assertThat(context).hasSingleBean(FlowEngine.class).doesNotHaveBean(NodeResolver.class);
                    assertThat(context.getBean(FlowEngine.class)).isSameAs(engine);
                });
        } finally { engine.close(); }
    }

    @Test
    void missingSpelAdapterPreventsAutoConfiguration() {
        runner.withClassLoader(new FilteredClassLoader(SpelConditionEvaluator.class))
            .run(context -> assertThat(context).doesNotHaveBean(FlowEngineAutoConfiguration.class));
    }

    @Test
    void ambiguousResolversFailInsteadOfChoosingArbitrarily() {
        runner.withBean("first", NodeResolver.class, () -> name -> node -> 1)
            .withBean("second", NodeResolver.class, () -> name -> node -> 2)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void primaryResolverIsUsedWhenMultipleCandidatesExist() {
        runner.withBean("first", NodeResolver.class, () -> name -> node -> 1)
            .withBean("preferred", NodeResolver.class, () -> name -> node -> 2, definition -> definition.setPrimary(true))
            .run(context -> {
                var engine = context.getBean(FlowEngine.class);
                engine.register("primary", FLOW);
                assertThat(engine.execute("primary", null).results().get("echo").value()).isEqualTo(2);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class BootHost {}
}
