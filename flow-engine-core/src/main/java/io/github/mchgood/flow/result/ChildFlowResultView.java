package io.github.mchgood.flow.result;

import java.util.Map;

/**
 * 成功子流程作为父调用节点输出的视图；内部节点结果不平铺到父流程。
 * <p>引擎传入 FlowResult 的只读结果 Map；直接构造本 record 不会额外复制或冻结传入 Map。
 *
 * @param status 子执行状态名称；引擎仅在子流程成功时发布此视图
 * @param executionId 子执行独立实例 ID
 * @param results 子执行内部节点的结果表，业务对象仍共享引用
 */
public record ChildFlowResultView(String status, String executionId, Map<String,NodeRecord> results) {}
