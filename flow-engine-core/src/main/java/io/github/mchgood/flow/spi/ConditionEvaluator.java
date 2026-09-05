package io.github.mchgood.flow.spi;

import io.github.mchgood.flow.node.NodeContext;

/**
 * 条件编译与求值扩展点，用于排他网关出边。
 * <p>parse 在注册阶段调用，evaluate 在工作线程执行；实现需支持并发使用。
 * 必须保持只读访问并严格返回 Boolean 语义，不以字符串或数字隐式转换为真假。
 * 默认边由引擎识别，不经过此接口。
 */
public interface ConditionEvaluator {

    /**
     * 解析并校验条件，不依赖某次执行的数据。
     *
     * @param expression 出边条件文本，不包含 Mermaid 标签分隔符
     * @param location 原始定义位置，供诊断使用
     * @return 可并发复用的编译条件
     * @throws io.github.mchgood.flow.exception.FlowException 条件语法错误或包含禁止的操作
     */
    CompiledCondition parse(String expression, SourceLocation location);

    /**
     * 在当前节点的受限可见范围内求值。
     *
     * @param expression 此求值器编译的条件
     * @param context 当前输入与已终结祖先快照
     * @return 条件是否匹配
     * @throws io.github.mchgood.flow.exception.FlowException 访问越界、类型不符或求值失败
     */
    boolean evaluate(CompiledCondition expression, NodeContext context);
}
