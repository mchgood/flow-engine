package io.github.mchgood.flow.spring;

import io.github.mchgood.flow.*;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.BeansException;

public final class SpringNodeResolver implements NodeResolver {
    private final ConfigurableListableBeanFactory factory;
    public SpringNodeResolver(ConfigurableListableBeanFactory factory){this.factory=factory;}
    @Override public FlowNode resolve(String id){
        try {
            String target=ScopedProxyUtils.getTargetBeanName(id);
            if(!factory.isSingleton(id)||(factory.containsBean(target)&&!factory.isSingleton(target)))throw new FlowException("BEAN_SCOPE_UNSUPPORTED",id);
            return factory.getBean(id,FlowNode.class);
        }catch(FlowException e){throw e;}catch(BeansException e){throw new FlowException("BEAN_BINDING_ERROR",id,e);}
    }
}
