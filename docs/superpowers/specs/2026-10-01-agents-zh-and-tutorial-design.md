# AGENTS.md 中文化 + 分层教学文档设计

- 日期:2026-10-01
- 状态:已评审通过(用户确认)
- 范围:纯文档变更——`AGENTS.md` 翻译、新增 `docs/tutorial.md`;不涉及任何 Java 代码、构建或行为变更

## 1. AGENTS.md 中文化

将 `AGENTS.md` 全文翻译为中文。翻译纪律(指令文件,保真优先):

- **不译**:文件路径、类/接口/包名、配置键、错误码、checkstyle 白名单(`i`/`j`/`k`/`id`/`to`)、阈值数字(LINE ≥ 95%、BRANCH ≥ 88%)、命令原文(`mvn verify`、`python3 scripts/check-coverage.py`、`config/checkstyle/checkstyle.xml`)
- **逐条保真**:Scope and authorization、Architecture(5 模块边界表)、Workflow invariants(8 条不变量)、Documentation and references、Verification(6 条)五节语义不增不减不弱化;翻译后该文件仍是后续 agent 会话的执行依据
- 模块表中 starter 行已含自动加载新描述(FlowSource 自动注册、绝不执行),一并翻译
- 相对链接(`docs/requirements.md` 等)保持不变

## 2. docs/tutorial.md 分层教程

中文,与现有文档互链,不重复 `docs/spring-boot.md` 的完整配置表(链接过去)。

```
# flow-engine 教程
第一部分 入门(从零到一可运行)
  1. 认识 flow-engine:定位(in-process 轻量编排)、适用边界、模块地图
  2. 跑通第一个流程:Maven 依赖 → FlowNode Bean → mermaid MD → 普通Spring
     手动装配(DefaultFlowEngine)→ execute → 读 FlowResult
  3. Spring Boot 路径:starter 自动装配 + classpath:flows/*.md 自动注册
  4. 核心概念:节点形状语义表、flowId 与 _ 别名规则、条件网关(排他)、
     +/- 并行分叉汇合、子流程调用、FlowResult 与错误码契约
  5. 常见问题:超时(三层期限)、失败传播与跳过语义、Bean 线程安全要求
第二部分 扩展实战(SPI)
  6. 扩展点全景表:何时用 NodeResolver / ConditionEvaluator / FlowSource /
     EngineConfig(含"宿主 Bean 覆盖即退让"约定)
  7. 实战 1:自定义 NodeResolver(非 Spring 容器场景,静态注册表)
  8. 实战 2:自定义 ConditionEvaluator(规则引擎示例;只读 + 严格 Boolean 约束)
  9. 实战 3:自定义 FlowSource(Nacos 式数据源;实现即接入,starter 零改动)
  10. EngineConfig 程序化覆盖
  11. 扩展点测试要点:错误码断言、线程安全、注册期校验失败路径
附录:文档地图(quick-start / spring-boot / requirements / technical-design /
testing-coverage 的分工与互链)
```

## 3. 准确性保障

- 所有示例代码(接口方法签名、构造器、配置键、错误码)从当前源码提取核对,禁止凭记忆编写:
  - `FlowNode`/`NodeContext`、`NodeResolver.resolve`、`ConditionEvaluator`/`CompiledCondition`、
    `FlowSource`/`FlowDocument`、`EngineConfig` 构造器、`DefaultFlowEngine` 构造器、
    `FlowEngine.register/registerAll/execute/close`、`FlowResult` 访问器
- Mermaid 示例只使用 README/quick-start 中已验证的语法(含 `fulfillment[["..."]]` 子流程写法)
- 教程中的行为描述与 `docs/requirements.md` 一致;有出入时以源码为准并在交付说明中报告

## 4. 验证(docs-only)

- 链接检查:新增文档内部锚点与互链、AGENTS.md 链接可达
- 示例一致性:教程代码签名与源码逐一核对
- 翻译保真:AGENTS.md 逐节对照原文,确认约束无增删弱化
- 不运行 Java 套件(无代码/构建/行为变更)
