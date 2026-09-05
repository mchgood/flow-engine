package io.github.mchgood.flow.result;

import java.time.Instant;

/**
 * 节点终态快照；状态按完整图节点 ID 隔离，不按 Bean ID 合并。
 * <p>记录本身不可变，但 value 中的业务对象不会被深拷贝。
 *
 * @param nodeId 包含调用别名的完整节点 ID
 * @param targetId 任务的 Bean ID 或子流程 ID；控制节点为 null
 * @param type 内部节点类型名称，如 TASK、CALL_FLOW、XOR_SPLIT
 * @param status 节点状态
 * @param present 是否成功；成功返回 null 时仍为 true
 * @param value 成功输出；子流程调用输出为 ChildFlowResultView
 * @param skipReason 跳过原因；未跳过时为 null
 * @param error 节点错误；无错误时为 null
 * @param startedAt 开始时间；尚未开始或直接跳过时可为 null
 * @param endedAt 终结时间；非终态快照可为 null
 * @param selectedEdgeId 排他网关选中边 ID；其他情况为 null
 */
public record NodeRecord(String nodeId, String targetId, String type, NodeStatus status,
    boolean present, Object value, String skipReason, FlowError error, Instant startedAt,
    Instant endedAt, String selectedEdgeId) {}
