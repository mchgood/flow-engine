# MD 多流程自动加载(本地文件 + 可扩展数据源)设计

- 日期:2026-10-01
- 状态:已评审通过(用户确认全部章节)
- 范围:`flow-engine-core`(新增 SPI 契约)、`flow-engine-spring-boot-starter`(本地文件实现与装配)

## 1. 背景与目标

当前 starter 的边界是"只装配基础设施,不自动加载或执行流程",宿主必须自行读取 MD 并调用
`FlowEngine.register/registerAll`。本设计为 starter 增加自动解析 MD 文件并注册流程的能力:

- 一个 MD 文件可包含多个流程,以一级标题(`# `)区分,标题即 flowId;
- 扫描路径可配置,且有默认值;classpath 与文件系统均支持;
- 默认开启;解析或注册失败时 fail-fast 让应用启动失败;
- 解析与加载分层设计:本次实现本地文件来源,后续 Nacos 等外部数据源通过 SPI 接入,starter 零改动。

非目标:不自动执行任何流程;不改变 core 的 `FlowCompiler` 单 mermaid 块契约;不实现 Nacos 模块本身。

## 2. 分层与扩展点

```
flow-engine-core(新增纯契约,零实现)
  io.github.mchgood.flow.spi.FlowSource      — 流程文档来源 SPI
  io.github.mchgood.flow.spi.FlowDocument    — record(sourceName, markdown)

flow-engine-spring-boot-starter(本次实现)
  io.github.mchgood.flow.boot.flows.MarkdownFlowParser      — H1 切分器,框架无关(无 Spring 类型)
  io.github.mchgood.flow.boot.flows.LocalMarkdownFlowSource — classpath/文件系统 location 解析
  io.github.mchgood.flow.boot.flows.FlowSourceRegistrar     — SmartInitializingSingleton 注册器
  FlowEngineAutoConfiguration 增加 2 个 Bean 装配
```

契约定义:

- `FlowDocument(String sourceName, String markdown)`:`sourceName` 用于错误定位(如
  `classpath*:flows/order.md` 或 Nacos 的 dataId),`markdown` 为整份文档原文。
- `FlowSource`:
  - `String name()`:来源实例名,用于跨来源冲突等错误信息;
  - `List<FlowDocument> load()`:返回该来源的全部文档;IO 类失败直接抛出异常,由 Registrar 转为启动失败。

数据流:

```
locations → LocalMarkdownFlowSource.load() → List<FlowDocument>
          → MarkdownFlowParser.split(逐文档) → Map<flowId, sectionMarkdown>
          → 跨来源汇总 → engine.registerAll(单次原子) → 失败即启动失败
```

未来 Nacos:独立模块仅依赖 core,实现 `FlowSource` 并注册为 Bean;starter 的 Registrar 通过
`ObjectProvider<FlowSource>` 自动收集全部来源 Bean,无需修改 starter。本地与 Nacos 来源可共存;
默认本地 Source 仅在容器中不存在任何 `FlowSource` Bean 时创建(`@ConditionalOnMissingBean`)。

## 3. MD 格式与切分规则(MarkdownFlowParser)

输入:整份文档字符串 + sourceName。规则:

1. 仅 `# `(单井号 ATX)行开启新 section;`## `及更深标题视为 section 内容,不切分;
2. ``` 或 `~~~` 围栏(含 mermaid 块)内的 `#` 行不切分,需跟踪围栏开关状态;
3. H1 文本 trim 后必须匹配 `[a-z][A-Za-z0-9]*`(与 `FlowCompiler` 的 flowId 规则一致,
   见 FlowCompiler.java:71),否则报新错误码 `INVALID_FLOW_HEADING`,消息含文件与行号;
4. section 输出从**各自 H1 行**起切片到下一个 H1 行之前(最后一个 section 到文件末尾),并把
   H1 之前的行以等量空行补齐,使 section 内行号与原文件行号一致——compiler 报错行号即原文件
   行号,且每个 section 恰含本段的 mermaid 块;
5. 首个 H1 之前的导语被忽略,但导语中不允许出现 mermaid 围栏块(避免归属歧义),parser 自检并报错
   (含行号);
6. 每个 section 必须恰好包含一个顶层 mermaid 围栏块(由 compiler 的 `MERMAID_BLOCK_COUNT`
   兜底,parser 保证每个 section 至少含一个 mermaid 块,零 mermaid 的 section 报错);
7. 整个文件无 H1 且无 mermaid 块(如 flows 目录下的 README)→ 静默跳过;有 mermaid 但无 H1 →
   报错(含行号),避免流程被静默丢弃;
8. 同一文件内重复 H1 → 报错(含行号)。

错误码约定:仅新增 `INVALID_FLOW_HEADING`(starter 层语义);其余复用 compiler 既有错误码。
Registrar 包装 `FlowException` 时附加上下文 `source=<sourceName>, flow=<flowId>`,行号保持原样。

## 4. 配置项

嵌入现有 `flow-engine` 前缀,`FlowEngineProperties` 新增嵌套类 `Flows`(properties 校验复用绑定机制):

```yaml
flow-engine:
  flows:
    enabled: true                # 默认 true;false 完全关闭自动加载(含自定义 FlowSource Bean)
    locations:                   # 默认 [classpath*:flows/*.md]
      - classpath*:flows/*.md
      - file:./flows/*.md
```

- `enabled`:默认 `true`。设为 `false` 时整个自动加载子系统关闭——不创建本地 Source,也不创建
  Registrar,用户自定义的 `FlowSource` Bean 同样不会被消费;
- `locations`:List<String>,每项为 Ant 模式或具体文件路径,经
  `PathMatchingResourcePatternResolver` 解析;支持 `classpath:`、`classpath*:`、`file:` 与绝对路径;
- 匹配结果按资源 URL 排序,保证多来源、多文件场景下的确定性;
- 文件名不以 `.md` 结尾的匹配资源忽略;
- 目录不存在或零匹配 → 静默跳过(允许应用不含任何流程文件);
- 资源打开/读取失败 → 启动失败(fail-fast),错误含资源 URL。

## 5. 注册与生命周期(FlowSourceRegistrar)

- 实现 `SmartInitializingSingleton`,所有单例初始化完成后触发;经 `ObjectProvider<FlowEngine>`
  获取引擎——用户自定义覆盖 `FlowEngine` Bean 时自动加载依然生效;经
  `ObjectProvider<FlowSource>` 收集全部来源 Bean;
- 来源集合为空或文档总数为零 → 直接返回;
- 跨来源 flowId 冲突 → 启动失败,错误含两个来源名;同批内重复 flowId 由 `registerAll` 的
  `DUPLICATE_FLOW` 兜底;
- 单次 `engine.registerAll(Map<flowId, markdown>)`:原子发布,跨文件、跨来源的子流程引用
  同批可用;
- 与手动注册的冲突:若宿主已手动 `register` 同名 flowId,Registrar 后执行时 `DUPLICATE_FLOW`
  导致启动失败——自动加载与手动注册共存时由宿主保证 flowId 不冲突(文档明示);
- 只注册、绝不执行。

自动装配(FlowEngineAutoConfiguration 新增):

- `LocalMarkdownFlowSource`:`@ConditionalOnMissingBean(FlowSource.class)` + 条件
  `flow-engine.flows.enabled`(默认 true,matchIfMissing = true);
- `FlowSourceRegistrar`:`SmartInitializingSingleton`,同样受 `flow-engine.flows.enabled` 控制。

## 6. 文档与边界同步

- `AGENTS.md` starter 行:"never load/execute flows automatically" 更新为 enabled 时可从
  `FlowSource` Bean 自动注册、绝不执行;
- `docs/requirements.md`:加载边界更新为"可选便捷适配,默认开启可关闭";新增 FlowSource SPI
  约定与 MD 多流程格式要求(H1 即 flowId 等);
- `docs/technical-design.md`:新增 SPI 与本地实现章节(§3 注册机制与 §13 Boot 装配联动更新);
- `docs/spring-boot.md`:配置表新增 `flow-engine.flows.*`、多流程 MD 示例、"不扫描流程文件"
  表述更新;
- `README.md`:快速开始补一句多流程文件自动加载说明。

## 7. 测试计划

- `MarkdownFlowParser` 单测:正常多 section 切分;围栏内 `#` 不切分;H2/H3 不切分;非法 H1
  (`INVALID_FLOW_HEADING` 含文件+行号);导语含 mermaid 报错;零 H1 零 mermaid 静默跳过;
  零 H1 有 mermaid 报错;CRLF/BOM 处理;文件内重复 H1;section 恰含一个 mermaid 的校验;
- `LocalMarkdownFlowSource` 单测:`classpath*:` 匹配多资源;`file:` 目录;绝对路径;具体单文件;
  不存在目录静默;非 .md 忽略;URL 排序确定性;IO 失败 fail-fast;
- Registrar 集成测试(`ApplicationContextRunner`):默认开启且注册成功;`flows.enabled=false`
  完全关闭;用户自定义 `FlowEngine` Bean 时仍加载;跨文件子流程引用同批注册成功;重复 flowId
  启动失败且错误含来源名;坏文件 fail-fast 且错误含文件+行号;与手动 `register` 冲突报
  `DUPLICATE_FLOW`;自定义 `FlowSource` Bean 时本地 Source 退位;
- 覆盖率:新增类纳入 aggregate,维持 LINE ≥ 95%、BRANCH ≥ 88%,不降低阈值。

## 8. 风险与权衡记录

- **各 section 的 definitionHash 为标题偏移空行补齐后的切片摘要**,不含其他段落内容与文件导语;
  行号通过空行补齐保持与原文件一致;
- **默认开启 + 默认路径**:classpath 恰好存在 `flows/*.md` 的存量应用行为会变化——这是用户
  明确选择的开箱即用取舍,`flow-engine.flows.enabled=false` 一键关闭;
- **导语归属**:导语不并入任何 section(空行补齐仅保持行号对齐,不引入导语内容),parser 通过
  "导语禁 mermaid"校验消除归属歧义。
