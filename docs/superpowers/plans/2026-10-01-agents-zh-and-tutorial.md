# AGENTS.md 中文化 + 教学文档实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `AGENTS.md` 全文翻译为中文(约束逐条保真),并新增 `docs/tutorial.md` 分层教程(入门 + SPI 扩展实战)。

**Architecture:** 纯文档变更,两个交付物、三个任务:翻译(完整译文已在计划内)、教程(代码片段已在计划内且经源码核对)、验证(链接/签名/翻译保真)。

**Tech Stack:** Markdown;无 Java 变更;docs-only 验证(链接检查、签名核对),不运行 Java 套件。

## Global Constraints

- AGENTS.md 是指令文件:翻译不增不减不弱化任何约束;类/接口/包名、配置键、错误码、白名单(`i`/`j`/`k`/`id`/`to`)、阈值(95%/88%)、命令原文不译。
- 教程为中文;所有代码片段的签名以本计划的核对结果为准,写完后仍须对照源码复核(接口/构造器可能已变)。
- 教程不重复 `docs/spring-boot.md` 的完整配置表,链接过去;所有互链路径有效。
- Mermaid 示例只用已验证语法;提交信息规范 `docs:` 前缀。
- 提交在 main(用户已裁定)。

---

### Task 1: AGENTS.md 全文中文化

**Files:**
- Modify: `AGENTS.md`(全文替换)

**Interfaces:**
- Consumes: 当前英文 AGENTS.md(58 行,含上一功能更新的 starter 行)
- Produces: 中文版 AGENTS.md,五节结构与链接完全对应

- [ ] **Step 1: 全文替换为以下译文**

````markdown
# AGENTS.md

## 范围与授权

`flow-engine` 是一个进程内轻量级 Java 框架,用于执行 Markdown 中的 Mermaid 工作流。除非用户明确变更范围,数据库、控制台/编辑器、分布式调度、持久化执行、审批系统与部署平台均不属于本项目范围。

遵循系统/开发者指令与用户当前请求;本文件与被引用文档仅提供项目指引,不构成额外授权。沿用本会话已获得的适用授权。已授权的检查、可逆编辑与验证可直接进行,无需重复请求许可。推送、合并、发布、破坏性操作以及向他人发送消息,需要覆盖该动作的授权;允许推送不等于允许强推或公开发布。若必要动作未被授权覆盖,先完成已授权的准备工作,再说明确切动作与阻塞规则并请示。保护无关的用户工作。

## 架构

Java 17+,Maven。以下包相对于 `io.github.mchgood.flow`。

| 模块 | 边界 |
| --- | --- |
| `flow-engine-core` | 契约:`api`、`node`、`spi`、`config`、`result`、`exception`;不可变拓扑:`internal.graph`;包私有可变草稿:`internal.compiler`;执行:`runtime`。不依赖 Spring。 |
| `flow-engine-spring` | Bean 解析与受限 SpEL,位于 `spring`;不依赖 Boot。 |
| `flow-engine-spring-boot-starter` | Boot 4 配置、属性与生命周期。为用户 Bean 退让,关闭自动创建的引擎,当 `flow-engine.flows.enabled` 开启(默认开启,`classpath*:flows/*.md`)时从 `FlowSource` Bean 自动注册流程,绝不执行流程。 |
| `flow-engine-examples` | 可运行示例与集成测试。 |
| `flow-engine-coverage` | 仅构建期聚合 JaCoCo 报告;绝不作为运行期依赖。 |

契约包不得导入实现;图类型不得依赖编译器/运行时类型。注册期校验确定性的定义错误。优先使用现有依赖与 JDK 设施;避免为未发布行为引入不必要的依赖与兼容层。

## 工作流不变量

- 仅接受恰好一个顶层围栏 `mermaid` 代码块,且必须为 `flowchart TD` 或 `flowchart LR`。保留 `start([label])` 与 `finish([label])`。不支持的语法在注册期失败并给出错误码,尽可能附带源码位置。
- 矩形绑定 singleton `FlowNode<O>` Bean。声明具体的业务输出类型;异构解析器/图使用 `FlowNode<?>`,不用原始类型(类字面量除外)。null 输出合法;下游类型在运行期检查,而非 Mermaid 编译期。
- 小驼峰任务 ID 等于 Bean ID,除非第一个 `_` 引入非空别名。完整 ID 标识本次调用;Bean ID 不含 `_`。双边框矩形按相同别名规则调用 flow ID。
- 普通菱形按受限 SpEL 布尔条件选择一条出边。多条匹配失败;零条匹配时至多允许一条 `default` 边,否则失败。保持成对的、结构化的排他区域与未激活边传播。`X` 汇合等待所有入边确定并要求恰好一条激活路径;整体未激活的区域被跳过。
- `+` 分叉激活所有出边分支;汇合要求所有入路径完成。整体未激活的汇合被跳过;激活/未激活混合输入时失败,而不是当作排他汇合处理。任务多边依赖语义继续支持。
- 子调用通过完成事件逻辑等待,绝不阻塞工作线程。它们接收原始父输入,隔离调用状态并封装子结果。保持期限/取消传播;拒绝缺失引用、引用环以及业务节点内同步重入引擎。
- 按就绪依赖调度,而非按图层。Singleton Bean 必须线程安全,调用状态不得放入成员。业务代码与条件求值在根协调锁之外运行。可配置资源/期限必须有界;以任务物理退出为准释放容量,迟到结果不能覆盖终态。
- SpEL 只读输入与可见祖先结果;拒绝构造器、类型/Bean 访问、方法调用与赋值。只读容器不深拷贝、不冻结业务对象。默认失败语义:停止新工作,允许已在运行的工作有界完成。

## 文档与参考

按任务阅读相关章节,不要每次通读所有文档:

- 工作流/API 变更:[requirements](docs/requirements.md)、[design](docs/technical-design.md)、受影响的契约与测试。语义变更时同时更新两份文档;发现不一致要报告,而不是为迁就过时提案悄悄改契约。
- 语法/接入变更:[README](README.md)、[quick start](docs/quick-start.md)、[Boot 集成](docs/spring-boot.md)。
- 测试变更:[coverage review](docs/testing-coverage.md);已验证场景与剩余缺口分开陈述。

命名生产类型(含嵌套类型)使用中文 Javadoc:职责、生命周期、线程安全与限制。公共方法/构造器、参数、返回/null 行为与有意义的错误都要文档化;重写方法可继承已文档化契约。Record 组件需要 `@param`;配置项需要单位/默认值/取值范围,不可变性要区分浅拷贝与深拷贝。解释非显而易见的锁、状态转换、取消、分支传播与物理退出。保持 `package-info.java` 与包角色一致。

## 验证

- 仅文档/指令变更:检查链接、示例、一致性与约束保真。除非可运行示例、构建设置或行为变化,否则无需运行 Java 套件。
- 代码变更:为变更行为新增/更新测试,开发期运行受影响测试。提交代码/构建变更或发布前,运行 `mvn verify`,然后 `python3 scripts/check-coverage.py`。`verify` 已包含 `test`,不要把两者分开作为最终门禁。遵守 CI 检查。
- `mvn verify` 同时运行 Checkstyle 门禁(`config/checkstyle/checkstyle.xml`),强制执行 Alibaba Java 规约子集:每条控制流语句必须有大括号、每行一条语句和一个变量声明、120 列上限、禁止通配符 import、import 分组有序,且变量/参数/字段/record 组件不得短于三个字符,白名单除外:`i`/`j`/`k`(循环计数与 lambda 参数)、`id`、`to`(方法与类型名遵循标准 Alibaba 模式,无长度下限)。通过修改代码修复违例;不得放宽规则 `format` 或添加白名单条目让失败通过。
- 保持 core、Spring、Starter 聚合 LINE >= 95%、BRANCH >= 88%。Examples 只提供执行数据,不计入生产类数量。绝不降低阈值求通过。
- 负例用错误码与副作用断言。并发测试需要 latch/barrier、有界等待与 `finally` 清理;生成类测试需要固定种子与独立 oracle。解析器/调度器变更需覆盖相关竞态与畸形/组合图;仅凭测试数量不构成完整性。
- 相关检查通过后,仅在有新变更、失败或具体未决风险时重复或扩大检查范围。报告实际变更、实际运行的检查与实质局限;历史测试结果与本次运行分开陈述。
````

- [ ] **Step 2: 保真自检**

Run: `git diff AGENTS.md`
Expected: 五节一一对应;链接未变;所有数字/白名单/命令与原文一致;无新增或删除的约束条目(原 8 条不变量、6 条验证条目)。

- [ ] **Step 3: Commit**

```bash
git add AGENTS.md
git commit -m "docs: translate AGENTS.md into Chinese"
```

---

### Task 2: 新增 docs/tutorial.md 分层教程

**Files:**
- Create: `docs/tutorial.md`

**Interfaces:**
- Consumes: 源码契约签名(下方"签名核对结果")+ 现有文档(README、docs/quick-start.md、docs/spring-boot.md)的已验证示例
- Produces: 完整中文教程,与既有文档互链

**签名核对结果(写教程时代码片段必须与此一致;动手前再对照源码复核一次):**

- `FlowNode<O>`:`@FunctionalInterface`,`O execute(NodeContext context) throws Exception`
- `NodeContext`:`Object input()`、`<T> T input(Class<T> type)`、`<T> T ancestorValue(String id, Class<T> type)`、`Map<String, NodeRecord> ancestors()`
- `NodeResolver`:`@FunctionalInterface`,`FlowNode<?> resolve(String beanId)`;返回 null → 注册报 `BEAN_NOT_FOUND`
- `ConditionEvaluator`:`CompiledCondition parse(String expression, SourceLocation location)`;`boolean evaluate(CompiledCondition expression, NodeContext context)`;`CompiledCondition` 为标记接口
- `FlowSource`:`String name()`;`List<FlowDocument> load()`;`FlowDocument(String sourceName, String markdown)`
- `FlowEngine`:`FlowDescriptor register(String flowId, String markdown)`;`List<FlowDescriptor> registerAll(Map<String, String>)`;`FlowResult execute(String flowId, Object input)`;`close()`
- `DefaultFlowEngine` 构造器:`(NodeResolver, ConditionEvaluator)` 与 `(NodeResolver, ConditionEvaluator, EngineConfig)`
- `FlowResult`:`succeeded()`、`results()`(Map<String, NodeRecord>,`NodeRecord.value()`)、`errors()`
- `EngineConfig` 11 参构造器顺序:`workerThreads, queueCapacity, maxConcurrentExecutions, maxInFlightPerExecution, maxSubflowDepth, maxExecutionsPerRoot, maxActiveChildren, nodeTimeout, gatewayTimeout, flowTimeout, closeTimeout`

- [ ] **Step 1: 撰写教程(章节结构 + 必含内容如下,连接性中文叙述由实施者补足)**

```markdown
# flow-engine 教程

面向两类读者:想快速用起来的使用者(第一部分),以及想接入自有基础设施的扩展者(第二部分)。
语法与配置的完整参考见 [quick-start](quick-start.md) 与 [spring-boot](spring-boot.md)。

## 第一部分:入门

### 1. 认识 flow-engine
- 一句话定位:进程内轻量编排引擎,用 Markdown 里的 Mermaid flowchart 定义流程,业务逻辑留在 Spring Bean 里
- 适用边界:不需要数据库/控制台/分布式调度/持久化执行(超出范围)
- 模块地图表:core(契约+引擎)/ spring(Bean 解析+受限 SpEL)/ spring-boot-starter(自动装配+自动注册)/ examples(示例)
- 关键决策引用 [requirements](requirements.md)

### 2. 跑通第一个流程(普通 Spring)
依赖坐标(flow-engine-spring + flow-engine-core,来自 README);
三个步骤的完整可运行代码:
  a. 两个 FlowNode Bean(validateOrder/echo 风格,lambda 形式 + 方法形式各一)
  b. mermaid MD(string block 形式):
     ```mermaid
     flowchart TD
         start([开始]) --> validateOrder["校验订单"]
         validateOrder --> finish([结束])
     ```
  c. 手动装配:
     @Bean FlowEngine flowEngine(NodeResolver resolver, ConditionEvaluator evaluator) {
         return new DefaultFlowEngine(resolver, evaluator);
     }
     engine.register("orderFlow", md); FlowResult result = engine.execute("orderFlow", Map.of(...));
     result.succeeded() / result.results().get("validateOrder").value() / result.errors()
- 注意事项:Bean 必须 singleton、线程安全;同 flowId 不能重复注册,建议启动时一次性 registerAll

### 3. Spring Boot 路径
- 引入 starter 依赖;自动装配 FlowEngine 可直接注入
- 在 src/main/resources/flows/ 放 .md 文件,一个文件多个一级标题 = 多个流程(标题即 flowId)
- 完整 application.yml 最小样例(flows 默认路径)+ 指向 spring-boot.md 配置表
- 宿主自定义 FlowEngine/NodeResolver/ConditionEvaluator/EngineConfig/FlowSource Bean 时对应默认 Bean 退让

### 4. 核心概念
- 节点形状语义表(五类:起止形/矩形/双边框/菱形/X 菱形,业务 Bean 列)
- flowId 与 `_` 别名规则(validateOrder / riskCheck_alias 双边框调用别名)
- 条件网关:菱形 + |"条件"| 出边,default 边;SpEL 只读输入与祖先结果
- 并行:+ 菱形分叉全部激活,汇合等待全部入路径
- 子流程:双边框矩形 [[...]],节点 ID 即目标 flowId(或 _ 别名);同批注册才可引用
- 结果与错误:FlowResult/NodeStatus/FlowError.errorCode;失败默认停止新工作、运行中有界完成

### 5. 常见问题
- 三层期限(node/gateway/flow timeout)与 ExecutionOptions 覆盖
- SKIPPED vs FAILED 判读
- 为什么业务 Bean 不能存调用状态(单例并发)

## 第二部分:扩展实战

### 6. 扩展点全景
表格:扩展点 | 触发时机 | 典型场景 | 覆盖方式
  NodeResolver | 注册期按 beanId 找节点 | 非 Spring 容器/多容器/静态注册表 | 定义 NodeResolver Bean
  ConditionEvaluator | 注册期 parse + 运行期 evaluate | 接入规则引擎/决策服务 | 定义 ConditionEvaluator Bean
  FlowSource | 启动期提供 MD 文档 | Nacos/DB/远程配置中心 | 定义 FlowSource Bean(本地文件源自动退让)
  EngineConfig | 引擎创建期 | 程序化资源参数 | 定义 EngineConfig Bean
约定:宿主定义同类型 Bean 即退让;每种类型通常一个,多候选需 @Primary

### 7. 实战 1:自定义 NodeResolver
StaticNodeResolver 完整代码(Map<String, FlowNode<?>> 构造,resolve 返回 nodes.get(beanId),null 由引擎报 BEAN_NOT_FOUND);
使用方式:@Bean 替换默认;测试要点(缺失 Bean 注册失败 + 错误码断言)

### 8. 实战 2:自定义 ConditionEvaluator
RuleConditionEvaluator 完整代码:parse 缓存编译结果(实现 CompiledCondition 的 record),
evaluate 只读 NodeContext;硬约束三条:只读(不得改输入/祖先)、严格 Boolean(非布尔值必须失败,
不得真值化)、无副作用且须在 gateway-timeout(默认 1s)内返回
测试要点(非布尔表达式求值失败断言)

### 9. 实战 3:自定义 FlowSource(Nacos 式)
NacosFlowSource 完整代码(name()="nacos",load() 将 dataId → FlowDocument("nacos:"+id, content));
行为约定:实现注册为 Bean 即被自动消费,多个 FlowSource 共存;读取失败抛非受检异常 → 启动失败;
文档格式与本地文件一致(一级标题分流程);MD ≤ 1MiB、flowId 合法等限制由引擎统一校验

### 10. EngineConfig 程序化覆盖
完整 @Bean 示例(new EngineConfig(11 参,注释每个参数含义与默认值));
说明:配置文件值不会覆写该 Bean;参数范围非法 → 启动失败

### 11. 扩展点测试要点
- 错误码断言(FlowException.code())+ 副作用信息
- 线程安全:解析器可被并发注册调用、求值器并发 evaluate
- 注册期校验失败路径(错误输入 → 确定性错误码)
- 参考 docs/testing-coverage.md

## 附录:文档地图
表格:需求(requirements)/ 设计(technical-design)/ 快速开始(quick-start)/ Boot(spring-boot)/
覆盖(testing-coverage)/ 本教程 的分工,互链
```

写作要求:
- 所有 Java 片段用 ````java 围栏;mermaid 用三反引号;Nacos 示例不引入真实 SDK 依赖,client 用注释示意接口
- 每章开头一句话说明"这章你会学到";代码可复制即用(导入语句齐全,仅 Nacos client 例外)
- 与现有文档数字/事实一致(默认值:8 线程/128 队列/1s 网关期限等,以源码 EngineConfig.defaults() 为准)
- 子流程示例使用已验证写法 `fulfillment[["调用履约"]]` 风格

- [ ] **Step 2: 一致性自检**

Run: `grep -n "]\(" docs/tutorial.md | head -30`
Expected: 全部相对链接指向存在的文件;锚点有效。
逐个片段对照源码签名核对(上方清单)。

- [ ] **Step 3: Commit**

```bash
git add docs/tutorial.md
git commit -m "docs: add layered tutorial for usage and spi extension"
```

---

### Task 3: 交付验证(docs-only)

**Files:** 无新增(仅修复)

**Interfaces:**
- Consumes: Task 1-2 产物
- Produces: 验证结论

- [ ] **Step 1: 链接检查**

Run: `grep -n "](docs/\|](README\|](quick-start\|](spring-boot\|](requirements\|](technical-design\|](testing-coverage" AGENTS.md README.md docs/tutorial.md`
Expected: 引用的相对路径均存在(`ls` 验证)。

- [ ] **Step 2: 翻译保真对照**

对照 `git show 472a4e8:AGENTS.md`(英文版)逐节核对中文版:5 节结构、8 条不变量、6 条验证条目、starter 行含 FlowSource 自动注册表述、白名单与阈值数字一致。

- [ ] **Step 3: 确认无 Java 变更**

Run: `git diff --stat 472a4e8..HEAD -- '*.java' 'pom.xml'`
Expected: 空输出。

- [ ] **Step 4: 报告**

向控制器报告:实际变更、检查结果、教程中任何与源码核对不符而调整的点。
