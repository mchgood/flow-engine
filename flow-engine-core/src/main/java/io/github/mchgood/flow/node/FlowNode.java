package io.github.mchgood.flow.node;

/**
 * 业务任务的同步执行契约，输出类型由业务节点声明。
 * <p>泛型约束节点自身的返回类型，不提供 Mermaid 连线的编译期类型校验。
 * 引擎使用 {@code FlowNode<?>} 保存不同输出类型的节点，结果仍以异构对象存储。
 * <p>Spring 适配器要求 singleton Bean；同一 Bean 可被多个流程或不同别名节点并发调用，
 * 实现必须自行保证线程安全，不应把本次输入和执行状态写入 Bean 成员。
 * 任务应配合线程中断；子流程应使用图中的显式调用节点，禁止同步重入当前引擎。
 *
 * @param <O> 业务输出类型；无输出节点可声明 Void 并返回 null
 */
@FunctionalInterface
public interface FlowNode<O> {

    /**
     * 执行一个图节点实例。
     *
     * @param context 当前执行、节点标识以及祖先快照，非 null
     * @return 声明类型 O 的业务输出，可为 null；输出不会自动序列化或深拷贝
     * @throws Exception 业务失败，由引擎记录为节点失败并停止后续调度
     */
    O execute(NodeContext context) throws Exception;
}

