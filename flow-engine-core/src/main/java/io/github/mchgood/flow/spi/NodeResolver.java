package io.github.mchgood.flow.spi;

import io.github.mchgood.flow.node.FlowNode;

/**
 * 将任务的目标 Bean ID 解析为可调用节点的扩展点。
 * <p>仅在注册期绑定任务节点，不处理网关和子流程。解析结果保存在编译图中并跨执行复用；
 * 解析器需支持并发注册，返回节点需支持并发调用。Spring 实现必须返回容器代理而非解包目标。
 */
@FunctionalInterface
public interface NodeResolver {

    /**
     * 解析任务目标。
     *
     * @param beanId 去掉第一个下划线及调用别名后的业务 Bean ID
     * @return 可复用的业务节点；返回 null 将导致注册报 BEAN_NOT_FOUND
     * @throws io.github.mchgood.flow.exception.FlowException 无法解析或节点作用域不符合约定
     */
    FlowNode<?> resolve(String beanId);
}

