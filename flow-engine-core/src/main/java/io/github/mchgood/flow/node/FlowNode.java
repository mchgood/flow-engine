package io.github.mchgood.flow.node;

/**
 * 业务任务的同步执行契约。
 * <p>Spring 适配器要求 singleton Bean；同一 Bean 可被多个流程或不同别名节点并发调用，
 * 实现必须自行保证线程安全，不应把本次输入和执行状态写入 Bean 成员。
 * 任务应配合线程中断；子流程应使用图中的显式调用节点，禁止同步重入当前引擎。
 */
@FunctionalInterface
public interface FlowNode {

    /**
     * 执行一个图节点实例。
     *
     * @param context 当前执行、节点标识以及祖先快照，非 null
     * @return 任意业务输出，可为 null；输出不会自动序列化或深拷贝
     * @throws Exception 业务失败，由引擎记录为节点失败并停止后续调度
     */
    Object execute(NodeContext context) throws Exception;
}

