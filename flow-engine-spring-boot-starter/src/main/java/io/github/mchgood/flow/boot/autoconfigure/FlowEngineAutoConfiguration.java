package io.github.mchgood.flow.boot.autoconfigure;

import io.github.mchgood.flow.api.FlowEngine;
import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.runtime.DefaultFlowEngine;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.NodeResolver;
import io.github.mchgood.flow.spring.SpelConditionEvaluator;
import io.github.mchgood.flow.spring.SpringNodeResolver;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Installs defaults only; the host owns flow registration and execution. */
@AutoConfiguration
@ConditionalOnClass({FlowEngine.class, SpelConditionEvaluator.class})
@ConditionalOnProperty(prefix = "flow-engine", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FlowEngineProperties.class)
public class FlowEngineAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(NodeResolver.class)
    public SpringNodeResolver flowNodeResolver(ConfigurableListableBeanFactory beans) {
        return new SpringNodeResolver(beans);
    }

    @Bean
    @ConditionalOnMissingBean(ConditionEvaluator.class)
    public SpelConditionEvaluator flowConditionEvaluator() {
        return new SpelConditionEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean(EngineConfig.class)
    public EngineConfig flowEngineConfig(FlowEngineProperties properties) {
        return properties.toEngineConfig();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(FlowEngine.class)
    public DefaultFlowEngine flowEngine(NodeResolver resolver, ConditionEvaluator evaluator, EngineConfig config) {
        return new DefaultFlowEngine(resolver, evaluator, config);
    }
}
