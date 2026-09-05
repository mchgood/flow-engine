package io.github.mchgood.flow.result;

/**
 * 一次执行错误的诊断记录；用于结果检查，不保存 Throwable 或堆栈。
 *
 * @param code 稳定错误码，调用方应判断此字段而非解析 message
 * @param message 诊断文本，不作为机器判断协议
 * @param executionId 发生错误的执行实例 ID
 * @param nodeId 关联的完整图节点 ID；流程级错误可为 null
 * @param callPath 从根流程到当前子执行的调用路径
 */
public record FlowError(String code, String message, String executionId, String nodeId, String callPath) {}
