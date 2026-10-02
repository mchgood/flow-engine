package io.github.mchgood.flow.boot.autoconfigure;

import io.github.mchgood.flow.api.FlowEngine;
import io.github.mchgood.flow.boot.flows.FlowSourceRegistrar;
import io.github.mchgood.flow.boot.flows.LocalMarkdownFlowSource;
import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.runtime.DefaultFlowEngine;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.FlowExecutionInterceptor;
import io.github.mchgood.flow.spi.FlowSource;
import io.github.mchgood.flow.spi.NodeExecutionInterceptor;
import io.github.mchgood.flow.spi.NodeResolver;
import io.github.mchgood.flow.spring.SpelConditionEvaluator;
import io.github.mchgood.flow.spring.SpringNodeResolver;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot 引擎自动装配入口，通过 AutoConfiguration.imports 发现。
 * <p>flow-engine.enabled 默认为 true。基础设施分别按类型退让，允许宿主覆盖
 * 节点解析器、条件求值器、资源配置、整个引擎或 FlowSourceRegistrar。flow-engine.flows.enabled 默认开启时，
 * 在全部单例就绪后把 FlowSource 提供的 Markdown 文档按一级标题切分并原子注册（本地默认
 * 扫描 classpath*:flows/*.md，可用配置覆盖或整体关闭）；绝不触发执行。
 * 自动创建引擎由容器在关闭时调用 close；核心与普通 Spring 模块不依赖本配置。
 */
@AutoConfiguration
@ConditionalOnClass({FlowEngine.class, SpelConditionEvaluator.class})
@ConditionalOnProperty(prefix = "flow-engine", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FlowEngineProperties.class)
public class FlowEngineAutoConfiguration {

    /**
     * 装配容器节点解析器。
     *
     * @param beans 宿主 BeanFactory
     * @return 保留 Spring 代理的默认解析器
     */
    @Bean
    @ConditionalOnMissingBean(NodeResolver.class)
    public SpringNodeResolver flowNodeResolver(ConfigurableListableBeanFactory beans) {
        return new SpringNodeResolver(beans);
    }

    /**
     * 装配受限 SpEL 求值器。
     *
     * @return 默认的只读条件求值器
     */
    @Bean
    @ConditionalOnMissingBean(ConditionEvaluator.class)
    public SpelConditionEvaluator flowConditionEvaluator() {
        return new SpelConditionEvaluator();
    }

    /**
     * 把启动配置转为不可变引擎配置；自定义 EngineConfig 存在时跳过此工厂。
     *
     * @param properties 已完成绑定的配置属性
     * @return 校验后的引擎配置
     * @throws IllegalArgumentException 配置超出核心允许范围
     */
    @Bean
    @ConditionalOnMissingBean(EngineConfig.class)
    public EngineConfig flowEngineConfig(FlowEngineProperties properties) {
        return properties.toEngineConfig();
    }

    /**
     * 装配由容器管理关闭的共享引擎；容器内全部流程与节点拦截器 Bean 按顺序收集。
     *
     * @param resolver 节点解析器
     * @param evaluator 条件求值器
     * @param config 固定资源配置
     * @param flowInterceptors 流程级拦截器提供者，可为空
     * @param nodeInterceptors 节点级拦截器提供者，可为空
     * @return 标准执行器
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(FlowEngine.class)
    public DefaultFlowEngine flowEngine(NodeResolver resolver, ConditionEvaluator evaluator, EngineConfig config,
            ObjectProvider<FlowExecutionInterceptor> flowInterceptors,
            ObjectProvider<NodeExecutionInterceptor> nodeInterceptors) {
        return new DefaultFlowEngine(resolver, evaluator, config, flowInterceptors.orderedStream().toList(),
                nodeInterceptors.orderedStream().toList());
    }

    /**
     * 装配本地文件流程来源；宿主已定义 FlowSource Bean 时退让。
     *
     * @param properties 已完成绑定的配置属性
     * @return 默认本地来源
     */
    @Bean
    @ConditionalOnMissingBean(FlowSource.class)
    @ConditionalOnProperty(prefix = "flow-engine.flows", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public LocalMarkdownFlowSource localMarkdownFlowSource(FlowEngineProperties properties) {
        return new LocalMarkdownFlowSource(properties.getFlows().getLocations(), properties.getFlows().toLimits());
    }

    /**
     * 装配来源注册器，在全部单例就绪后执行一次注册；宿主自定义 FlowSourceRegistrar Bean 时此工厂退让。
     *
     * @param engines 引擎提供者，可为空
     * @param sources 来源提供者，可为空
     * @param properties 启动期加载预算
     * @return 自动加载注册器
     */
    @Bean
    @ConditionalOnMissingBean(FlowSourceRegistrar.class)
    @ConditionalOnProperty(prefix = "flow-engine.flows", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public FlowSourceRegistrar flowSourceRegistrar(ObjectProvider<FlowEngine> engines,
            ObjectProvider<FlowSource> sources, FlowEngineProperties properties) {
        return new FlowSourceRegistrar(engines, sources, properties.getFlows().toLimits());
    }
}
