package io.github.mchgood.flow.api;

import io.github.mchgood.flow.result.FlowResult;

import java.util.*;
public interface FlowEngine extends AutoCloseable {
    FlowDescriptor register(String flowId,String markdown);
    List<FlowDescriptor> registerAll(Map<String,String> markdownByFlowId);
    FlowResult execute(String flowId,Object input,ExecutionOptions options);
    default FlowResult execute(String flowId,Object input){return execute(flowId,input,ExecutionOptions.defaults());}
    @Override void close();
}
