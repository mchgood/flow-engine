# flow-engine

一个嵌入 Spring 应用、以 Markdown 中 Mermaid 流程图为定义的轻量级 Java 流程执行框架。

## 当前能力

- 串行与显式并行分叉/汇合
- 菱形条件网关，出边使用受限 SpEL 表达式
- Spring `FlowNode` Bean 自动绑定
- 使用 `_alias` 在同一流程中多次调用同一个 Bean
- 使用 Mermaid 双边框节点同步调用子流程
- 流程、节点超时，并发与队列容量限制
- 每次执行独立的节点状态、结果和错误记录
- 注册阶段校验非法语法、缺失 Bean、环和子流程循环引用

项目定位是进程内执行框架，不提供数据库、管理平台、分布式调度或人工审批能力。

## 环境

- JDK 17+
- Maven 3.9+
- Spring Framework 7（Spring 适配模块）

## 快速开始

业务节点实现 `FlowNode` 并注册为 Spring Bean：

```java
@Bean
FlowNode validateOrder() {
    return context -> Map.of("valid", true);
}

@Bean(destroyMethod = "close")
FlowEngine flowEngine(ConfigurableListableBeanFactory beans) {
    return new DefaultFlowEngine(
        new SpringNodeResolver(beans),
        new SpelConditionEvaluator()
    );
}
```

使用 Mermaid 编排：

````markdown
```mermaid
flowchart TD
    start([开始]) --> validateOrder_before["前置校验"]
    validateOrder_before --> decision{"自动处理？"}
    decision -->|"#input.amount <= 1000"| fulfillment_main[["履约子流程"]]
    decision -->|"default"| manualReview["人工处理"]
    fulfillment_main --> merge{"X"}
    manualReview --> merge
    merge --> validateOrder_after["后置校验"]
    validateOrder_after --> finish([结束])
```
````

约定：

- 矩形节点 ID 默认就是 Spring Bean ID。
- `validateOrder_before` 和 `validateOrder_after` 都调用 `validateOrder` Bean，完整 ID 用于隔离两次调用的状态和结果。
- 普通菱形是一入多出的条件网关，恰好选择一条出边；`default` 最多一条。
- `{"+"}` 是并行分叉或并行汇合，汇合等待全部已激活输入。
- 双边框节点按 ID 查找子流程；例如 `fulfillment_main` 调用 `fulfillment` 流程。

注册并执行：

```java
engine.registerAll(Map.of(
    "orderFlow", orderMarkdown,
    "fulfillment", fulfillmentMarkdown
));

FlowResult result = engine.execute(
    "orderFlow",
    Map.of("amount", 800)
);
```

完整可运行代码见 `flow-engine-examples`。

## 模块

- `flow-engine-core`：Mermaid 子集编译、图校验、DAG 调度与公共 API。
- `flow-engine-spring`：Spring Bean 解析和安全受限的 SpEL 求值。
- `flow-engine-examples`：串行、条件、并行、别名及子流程组合示例。

主要包结构：

| 包（前缀 `io.github.mchgood.flow`） | 职责与主要类型 |
| --- | --- |
| `api` | 流程入口：FlowEngine、FlowDescriptor、ExecutionOptions |
| `node` | 业务节点契约：FlowNode、NodeContext |
| `spi` | 扩展适配契约：NodeResolver、ConditionEvaluator、CompiledCondition、SourceLocation |
| `config` | 引擎资源与期限配置：EngineConfig |
| `result` | 执行结果、节点状态和错误记录 |
| `exception` | 调用与定义错误：FlowException |
| `internal.compiler` | Mermaid 解析、图校验、Bean 绑定；MutableGraph 仅包内可见 |
| `internal.graph` | 不可变编译图 Definition，供编译器和运行时共享 |
| `runtime` | DefaultFlowEngine；调度、注册与每次执行状态 |
| `spring`（Spring 模块） | SpringNodeResolver、SpelConditionEvaluator |

依赖方向：`runtime → internal.compiler → internal.graph`，运行时也读取 `internal.graph`；图模型不依赖编译器或运行时。`api`、`node`、`spi`、`config`、`result`、`exception` 均不依赖实现包或 Spring。`node → result / exception`，`spi → node`，`api → result`。

`DefaultFlowEngine` 是可直接构造的实现入口；其协调器和执行状态仍为私有内部类，不为增加包数量而暴露。`internal.*` 中的 public 仅用于跨包协作，不属于兼容承诺。编译器把可变草稿复制为不可变拓扑、边和祖先集合，再交给运行时；Bean 实例和业务输出不作深拷贝。

本次为尚未发布版本的包名调整，使用方需更新 import；流程语法与执行行为不变。

## 验证

```bash
mvn verify
```

## 文档

- [快速使用](docs/quick-start.md)
- [需求文档](docs/requirements.md)
- [技术方案](docs/technical-design.md)

当前版本为原型阶段的 `0.1.0-SNAPSHOT`，API 尚未承诺兼容性。
