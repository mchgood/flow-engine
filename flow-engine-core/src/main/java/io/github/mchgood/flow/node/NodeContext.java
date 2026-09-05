package io.github.mchgood.flow.node;

import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.result.NodeOutput;
import io.github.mchgood.flow.result.NodeRecord;
import io.github.mchgood.flow.result.NodeStatus;

import java.util.*;
public final class NodeContext {
    private final String executionId, flowId, nodeId;
    private final Object input;
    private final Map<String,NodeRecord> ancestors;
    public NodeContext(String executionId,String flowId,String nodeId,Object input,Map<String,NodeRecord> ancestors) {
        this.executionId=executionId;this.flowId=flowId;this.nodeId=nodeId;this.input=input;
        this.ancestors=Collections.unmodifiableMap(new LinkedHashMap<>(ancestors));
    }
    public String executionId(){return executionId;}
    public String flowId(){return flowId;}
    public String nodeId(){return nodeId;}
    public <T> T input(Class<T> type){return type.cast(input);}
    public Object input(){return input;}
    public Map<String,NodeRecord> ancestors(){return ancestors;}
    public NodeStatus ancestorStatus(String id){return record(id).status();}
    public NodeOutput ancestorOutput(String id){var r=record(id);return new NodeOutput(r.present(),r.value());}
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
