package io.github.mchgood.flow.api;

/**
 * 已注册流程的轻量描述；用于确认定义标识，不暴露内部拓扑。
 *
 * @param flowId 流程注册 ID，不是某次执行 ID
 * @param definitionHash 原始 Markdown UTF-8 字节的 SHA-256 十六进制摘要，空白变化也会改变摘要
 * @param nodeCount 包含起止、网关及子流程调用节点在内的节点总数
 */
public record FlowDescriptor(String flowId,String definitionHash,int nodeCount) {}
