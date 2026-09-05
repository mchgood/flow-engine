package io.github.mchgood.flow.result;

import java.time.Instant;
import java.util.*;

/**
 * 单次流程执行的结果快照；根执行和子执行通过实例 ID 关联。
 * <p>构造时复制结果 Map 和诊断列表并禁止结构修改；业务输入与输出对象不深拷贝。
 * 逻辑终态不等于业务线程物理退出，应结合 physicalExitUnconfirmed 判断。
 *
 * @param executionId 当前执行实例 ID
 * @param rootExecutionId 整棵调用树的根执行 ID
 * @param parentExecutionId 直接父执行 ID；根流程为 null
 * @param flowId 已注册流程 ID
 * @param definitionHash 本次使用的定义摘要
 * @param status 流程终态；节点超时可能导致 FAILED，而流程期限耗尽为 TIMED_OUT
 * @param startedAt 执行开始时间
 * @param endedAt 结果生成时间
 * @param results 以完整图节点 ID 索引的节点快照
 * @param errors 执行诊断，可能包含子流程错误和父调用错误
 * @param physicalExitUnconfirmed 结果生成时尚未确认物理退出的任务路径与执行 ID；不随线程后续退出更新
 */
public record FlowResult(String executionId, String rootExecutionId, String parentExecutionId,
    String flowId, String definitionHash, FlowStatus status, Instant startedAt, Instant endedAt,
    Map<String,NodeRecord> results, List<FlowError> errors, List<String> physicalExitUnconfirmed) {

    /**
     * 复制并冻结结果表与诊断列表，保留业务输出引用。
     *
     * @throws NullPointerException 任一集合为 null，或错误/未退出列表含 null 元素
     */
    public FlowResult {
        results=Collections.unmodifiableMap(new LinkedHashMap<>(results));
        errors=List.copyOf(errors); physicalExitUnconfirmed=List.copyOf(physicalExitUnconfirmed);
    }

    /**
     * 判断本次执行是否成功。
     *
     * @return 仅当 status 为 SUCCEEDED 时返回 true
     */
    public boolean succeeded() { return status==FlowStatus.SUCCEEDED; }
}
