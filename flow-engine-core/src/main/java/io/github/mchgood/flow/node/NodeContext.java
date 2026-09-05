package io.github.mchgood.flow.node;

import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.result.NodeOutput;
import io.github.mchgood.flow.result.NodeRecord;
import io.github.mchgood.flow.result.NodeStatus;

import java.util.*;

/**
 * 传递给业务节点与条件求值器的只读上下文快照。
 * <p>只暴露当前执行的静态祖先，包括被跳过的分支；不能读取后继、并行兄弟或父流程内部结果。
 * 祖先 Map 在构造时复制并冻结；输入及结果 value 仅共享引用，不代表业务对象深度不可变。
 */
public final class NodeContext {
    private final String executionId, flowId, nodeId;
    private final Object input;
    private final Map<String,NodeRecord> ancestors;

    /**
     * 构造上下文；通常由引擎在任务提交前创建。
     *
     * @param executionId 当前执行实例 ID
     * @param flowId 当前流程定义 ID
     * @param nodeId 完整图节点 ID，包含别名
     * @param input 原始输入，可为 null
     * @param ancestors 已终结祖先节点快照，非 null
     * @throws NullPointerException ancestors 为 null
     */
    public NodeContext(String executionId,String flowId,String nodeId,Object input,Map<String,NodeRecord> ancestors) {
        this.executionId=executionId;this.flowId=flowId;this.nodeId=nodeId;this.input=input;
        this.ancestors=Collections.unmodifiableMap(new LinkedHashMap<>(ancestors));
    }

    /**
     * 返回本次执行 ID。
     *
     * @return 本次执行 ID
     */
    public String executionId(){return executionId;}

    /**
     * 返回当前流程 ID。
     *
     * @return 当前流程 ID
     */
    public String flowId(){return flowId;}

    /**
     * 返回含调用别名的完整图节点 ID。
     *
     * @return 含调用别名的完整图节点 ID
     */
    public String nodeId(){return nodeId;}

    /**
     * 按 Java 类型读取输入，不做转换或反序列化。
     *
     * @param <T> 输入目标类型
     * @param type 非 null 的类型令牌
     * @return 输入对象；输入为 null 时返回 null
     * @throws ClassCastException 输入与目标类型不兼容
     * @throws NullPointerException type 为 null
     */
    public <T> T input(Class<T> type){return type.cast(input);}

    /**
     * 读取原始输入引用。
     *
     * @return 原始输入，可为 null
     */
    public Object input(){return input;}

    /**
     * 读取当前执行的祖先快照，包含跳过的祖先。
     *
     * @return 不可修改的 Map，键为完整图节点 ID
     */
    public Map<String,NodeRecord> ancestors(){return ancestors;}

    /**
     * 读取祖先状态。
     *
     * @param id 祖先完整节点 ID
     * @return 祖先状态
     * @throws FlowException ID 不存在或不是可见祖先，错误码 CONTEXT_ACCESS_DENIED
     */
    public NodeStatus ancestorStatus(String id){return record(id).status();}

    /**
     * 读取输出存在性及原始值。
     *
     * @param id 祖先完整节点 ID
     * @return 输出存在性及原始值
     * @throws FlowException ID 不存在或不是可见祖先，错误码 CONTEXT_ACCESS_DENIED
     */
    public NodeOutput ancestorOutput(String id){var r=record(id);return new NodeOutput(r.present(),r.value());}

    /**
     * 按类型读取成功祖先输出；成功返回 null 与没有输出分别处理。
     *
     * @param <T> 输出目标类型
     * @param id 祖先完整节点 ID
     * @param type 非 null 的类型令牌
     * @return 祖先业务输出，可为 null
     * @throws FlowException 非祖先为 CONTEXT_ACCESS_DENIED；无成功输出为 MISSING_NODE_OUTPUT
     * @throws ClassCastException 输出类型不兼容
     * @throws NullPointerException type 为 null 且已找到成功输出
     */
    public <T> T ancestorValue(String id,Class<T> type){
        var out=ancestorOutput(id);
        if(!out.present())throw new FlowException("MISSING_NODE_OUTPUT",id);
        return type.cast(out.value());
    }
    private NodeRecord record(String id){
        var r=ancestors.get(id);
        if(r==null)throw new FlowException("CONTEXT_ACCESS_DENIED","Not an ancestor: "+id);
        return r;
    }
}
