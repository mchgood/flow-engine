package io.github.mchgood.flow.api;

import io.github.mchgood.flow.result.FlowResult;

import java.util.*;

/**
 * 流程注册、同步执行和生命周期入口。
 * <p>标准实现允许并发调用；注册结果以不可变快照发布，同一个 flowId 不允许覆盖。
 * 宿主负责流程加载与执行时机；业务执行失败通常体现在 FlowResult，接入或准入错误抛出异常。
 * 引擎应作为共享实例使用，在应用关闭时关闭，不应每次执行后关闭。
 */
public interface FlowEngine extends AutoCloseable {

    /**
     * 注册一个流程；包含子流程引用时，被引用流程必须已经注册。
     *
     * @param flowId 小驼峰流程 ID
     * @param markdown 含且仅含一个顶层 Mermaid 代码块的 Markdown，非 null
     * @return 注册后的描述信息
     * @throws io.github.mchgood.flow.exception.FlowException 定义、绑定或引用校验失败，ID 重复，或引擎已关闭
     * @throws NullPointerException 参数为 null
     */
    FlowDescriptor register(String flowId,String markdown);

    /**
     * 原子注册一组流程，允许本批次内相互引用，但不允许循环引用。
     * <p>任一候选失败时不发布整批定义；节点解析器自身的外部副作用不会回滚。
     * 标准实现对空 Map 直接返回空列表，即使引擎已经关闭。
     *
     * @param markdownByFlowId 流程 ID 到 Markdown 的映射，非 null
     * @return 按输入 Map 遍历顺序排列的描述列表
     * @throws io.github.mchgood.flow.exception.FlowException 编译、重复 ID、子流程引用或生命周期校验失败
     * @throws NullPointerException Map 为 null
     */
    List<FlowDescriptor> registerAll(Map<String,String> markdownByFlowId);

    /**
     * 同步执行已注册流程，等待逻辑结果返回。
     * <p>调用线程参与协调，不在工作线程内阻塞等待子流程。标准实现被调用线程中断时停止执行树、
     * 返回失败结果并恢复中断标记；超时或中断不能保证不响应中断的业务代码物理退出。
     *
     * @param flowId 已注册流程 ID
     * @param input 输入，可为 null；直接共享对象引用，宿主应避免并发修改
     * @param options 非 null 的本次执行选项
     * @return 包含成功、失败或超时状态的结果，不应仅凭未抛异常判断成功
     * @throws io.github.mchgood.flow.exception.FlowException 流程不存在、根调用容量耗尽、引擎已关闭或工作线程同步重入
     * @throws NullPointerException options 为 null
     */
    FlowResult execute(String flowId,Object input,ExecutionOptions options);

    /**
     * 使用引擎默认流程期限同步执行。
     *
     * @param flowId 已注册流程 ID
     * @param input 可为 null 的流程输入
     * @return 本次执行结果
     * @see #execute(String, Object, ExecutionOptions)
     */
    default FlowResult execute(String flowId,Object input){return execute(flowId,input,ExecutionOptions.defaults());}

    /**
     * 关闭引擎并拒绝新的根调用和非空注册。
     * <p>标准实现先限时等待已有根调用，再终结残留执行树并请求中断工作线程。
     * 此操作不回滚业务副作用，也不保证忽略中断的任务已经退出。
     *
     * @throws io.github.mchgood.flow.exception.FlowException 当前引擎工作线程重入调用 close
     */
    @Override void close();
}
