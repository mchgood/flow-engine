package io.github.mchgood.flow.boot.flows;

import io.github.mchgood.flow.api.FlowEngine;
import io.github.mchgood.flow.boot.autoconfigure.FlowEngineAutoConfiguration;
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.runtime.DefaultFlowEngine;
import io.github.mchgood.flow.spi.FlowDocument;
import io.github.mchgood.flow.spi.FlowSource;
import io.github.mchgood.flow.spring.SpelConditionEvaluator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证自动加载的默认路径、开关、来源扩展点、fail-fast 与引擎覆盖场景。
 */
class FlowSourceRegistrarTest {
    private static final String PLAIN = """

        ```mermaid
        flowchart TD
            start([开始]) --> check["检查"]
            check --> finish([结束])
        ```
        """;

    private final ApplicationContextRunner runner = new ApplicationContextRunner().
        withConfiguration(AutoConfigurations.of(FlowEngineAutoConfiguration.class)).
        withUserConfiguration(CheckNode.class, PackNode.class);

    @Test
    void loadsFlowsFromDefaultClasspathLocation() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            FlowEngine engine = context.getBean(FlowEngine.class);
            FlowException failure = assertThrows(FlowException.class, () -> engine.register("orderFlow", PLAIN));
            assertThat(failure.code()).isEqualTo("DUPLICATE_FLOW");
            assertThat(engine.execute("orderFlow", null).succeeded()).isTrue();
        });
    }

    @Test
    void flowsEnabledFalseSkipsLoading() {
        runner.withPropertyValues("flow-engine.flows.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().
                doesNotHaveBean(LocalMarkdownFlowSource.class).doesNotHaveBean(FlowSourceRegistrar.class);
            context.getBean(FlowEngine.class).register("orderFlow", PLAIN);
        });
    }

    @Test
    void customSourceReplacesLocalSource() {
        TestSource source = new TestSource("custom",
            List.of(new FlowDocument("memory:custom.md", document("customFlow"))), new AtomicInteger());
        runner.withBean(FlowSource.class, () -> source).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(LocalMarkdownFlowSource.class);
            assertThat(context.getBean(FlowEngine.class).execute("customFlow", null).succeeded()).isTrue();
            assertThat(source.loads().get()).isEqualTo(1);
        });
    }

    @Test
    void customEngineStillReceivesLoadedFlows() {
        DefaultFlowEngine engine = new DefaultFlowEngine(id -> context -> context.input(),
            new SpelConditionEvaluator());
        try {
            runner.withBean("customEngine", FlowEngine.class, () -> engine).
                withPropertyValues("flow-engine.flows.locations=classpath*:it/flows/*.md").
                run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FlowEngine.class)).isSameAs(engine);
                    assertThat(engine.execute("orderFlow", null).succeeded()).isTrue();
                });
        } finally {
            engine.close();
        }
    }

    @Test
    void duplicateFlowAcrossSourcesFailsWithBothOrigins() {
        TestSource first = new TestSource("first",
            List.of(new FlowDocument("memory:one.md", document("dupFlow"))), new AtomicInteger());
        TestSource second = new TestSource("second",
            List.of(new FlowDocument("memory:two.md", document("dupFlow"))), new AtomicInteger());
        runner.withBean("firstSource", FlowSource.class, () -> first).
            withBean("secondSource", FlowSource.class, () -> second).
            run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).isInstanceOfSatisfying(FlowException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("DUPLICATE_FLOW");
                    assertThat(failure.getMessage()).contains("first").contains("second");
                });
            });
    }

    @Test
    void invalidDocumentFailsWithSourceContext() {
        TestSource source = new TestSource("custom",
            List.of(new FlowDocument("memory:bad.md", "# Bad-Name\n" + PLAIN)), new AtomicInteger());
        runner.withBean(FlowSource.class, () -> source).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOfSatisfying(FlowException.class, failure -> {
                assertThat(failure.code()).isEqualTo("INVALID_FLOW_HEADING");
                assertThat(failure.getMessage()).contains("memory:bad.md");
            });
        });
    }

    @Test
    void manualRegistrationConflictFailsStartup() {
        runner.withBean("squatter", BeanPostProcessor.class, () -> new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof FlowEngine engine) {
                    engine.register("orderFlow", PLAIN);
                }
                return bean;
            }
        }).withPropertyValues("flow-engine.flows.locations=classpath*:it/flows/*.md").
            run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).isInstanceOfSatisfying(FlowException.class,
                    failure -> assertThat(failure.code()).isEqualTo("DUPLICATE_FLOW"));
            });
    }

    @Test
    void registrarWithoutEngineSkipsSilently() {
        StaticApplicationContext context = new StaticApplicationContext();
        try {
            TestSource source = new TestSource("custom",
                List.of(new FlowDocument("memory:custom.md", document("customFlow"))), new AtomicInteger());
            context.registerBean("probe", FlowSource.class, () -> source);
            context.refresh();
            FlowSourceRegistrar registrar = new FlowSourceRegistrar(context.getBeanProvider(FlowEngine.class),
                context.getBeanProvider(FlowSource.class));
            registrar.afterSingletonsInstantiated();
            assertThat(source.loads().get()).isEqualTo(0);
        } finally {
            context.close();
        }
    }

    @Test
    void multiHeadingFileRegistersEverySection() {
        String multi = String.join("\n",
            "# masterFlow",
            "```mermaid",
            "flowchart TD",
            "    start([开始]) --> helperFlow[[\"调用辅助\"]]",
            "    helperFlow --> finish([结束])",
            "```",
            "# helperFlow",
            "```mermaid",
            "flowchart TD",
            "    start([开始]) --> check[\"处理\"]",
            "    check --> finish([结束])",
            "```");
        TestSource source = new TestSource("custom",
            List.of(new FlowDocument("memory:multi.md", multi + "\n")), new AtomicInteger());
        runner.withBean(FlowSource.class, () -> source).run(context -> {
            assertThat(context).hasNotFailed();
            FlowEngine engine = context.getBean(FlowEngine.class);
            assertThat(engine.execute("masterFlow", null).succeeded()).isTrue();
            assertThat(engine.execute("helperFlow", null).succeeded()).isTrue();
        });
    }

    private static String document(String flowId) {
        return "# " + flowId + "\n" + PLAIN;
    }

    /**
     * 自动加载测试资源所需的 check 节点。
     */
    @Component("check")
    static class CheckNode implements FlowNode<Object> {
        @Override
        public Object execute(NodeContext context) {
            return context.input();
        }
    }

    /**
     * 自动加载测试资源所需的 pack 节点。
     */
    @Component("pack")
    static class PackNode implements FlowNode<Object> {
        @Override
        public Object execute(NodeContext context) {
            return context.input();
        }
    }

    /**
     * 测试用来源：记录 load 调用次数并返回固定文档。
     */
    record TestSource(String label, List<FlowDocument> documents, AtomicInteger loads) implements FlowSource {
        @Override
        public String name() {
            return label;
        }

        @Override
        public List<FlowDocument> load() {
            loads.incrementAndGet();
            return documents;
        }
    }
}
