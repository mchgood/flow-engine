package io.github.mchgood.flow.spring;

import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.spi.NodeResolver;


import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.BeansException;

/**
 * 从宿主 Spring 容器按名称解析 singleton FlowNode 的适配器。
 * <p>保留容器返回的 AOP 代理，不解包目标对象；同时检查作用域代理背后的目标作用域。
 * 业务 Bean 的创建及销毁由 Spring 管理，本适配器不持有独立业务实例。
 */
public final class SpringNodeResolver implements NodeResolver {
    private final ConfigurableListableBeanFactory factory;

    /**
     * 使用宿主 BeanFactory 创建解析器。
     *
     * @param factory 已配置的宿主 BeanFactory，调用方应传非 null
     */
    public SpringNodeResolver(ConfigurableListableBeanFactory factory){this.factory=factory;}

    /**
     * {@inheritDoc}
     * <p>非 singleton 报 BEAN_SCOPE_UNSUPPORTED；缺失、类型不匹配或容器解析失败报 BEAN_BINDING_ERROR。
     */
    @Override public FlowNode resolve(String id){
        try {
            String target=ScopedProxyUtils.getTargetBeanName(id);
            if(!factory.isSingleton(id)||(factory.containsBean(target)&&!factory.isSingleton(target)))throw new FlowException("BEAN_SCOPE_UNSUPPORTED",id);
            return factory.getBean(id,FlowNode.class);
        }catch(FlowException e){throw e;}catch(BeansException e){throw new FlowException("BEAN_BINDING_ERROR",id,e);}
    }
}
