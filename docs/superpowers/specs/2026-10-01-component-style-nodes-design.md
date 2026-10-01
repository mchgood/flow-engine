# FlowNode 示例全面改为 @Component 形式设计

- 日期:2026-10-01
- 状态:已评审通过(用户确认)
- 范围:文档 4 个文件、examples 1 个类、测试 4 个类;生产代码零变更(`SpringNodeResolver` 按名称查找,本就支持 @Component)

## 1. 背景

现有文档/示例/测试均以 `@Bean` 方法演示 FlowNode 注册,误导用户以为 @Component 不可用。实际
`SpringNodeResolver.resolve` 只做 `factory.getBean(id, FlowNode.class)` 按名称查找,注册方式无关。
示例用 @Bean 的历史原因是示例节点为 lambda(无法加注解)。

## 2. 转换原则

- 所有 FlowNode 定义改为 `@Component` 命名类;Bean 名 = 图中节点 ID:
  - 类名首字母小写恰好等于节点 ID → 直接 `@Component`(如 `ValidateOrder` → `validateOrder`);
  - 否则显式 `@Component("节点ID")`;
  - 文档须说明 Spring 默认命名规则(Introspector.decapitalize)及"不确定就显式命名"的建议。
- 非 FlowNode Bean 一律保留 @Bean/withBean:`FlowEngine` 手动装配、`NodeResolver`、
  `ConditionEvaluator`、`EngineConfig`、`FlowSource` 覆盖 Bean。
- 文档在节点示例附近保留一句:lambda 或动态场景仍可用 @Bean 方法注册。

## 3. 变更清单

### 文档(4)

- `README.md`:validateOrder 示例改 @Component 类;新增 Bean 名命名规则一句。
- `docs/quick-start.md`:5 个节点 @Bean(validateOrder、autoProcess、manualReview(FlowConfiguration)
  与第 9 章 saveOrder、泛型小节 validateOrder)→ 5 个 @Component 类;`FlowEngine` 手动装配 @Bean 保留;33 行附近的说明补命名规则。
- `docs/spring-boot.md`:DemoApplication 的 greet 节点 → @Component 类。
- `docs/tutorial.md`:第 2 章节点示例 → @Component 类;第 2/4 章补命名规则与 @Bean 兼容说明;
  第 7 章自定义 NodeResolver 一节的表述与 @Component 默认路径保持一致。

### 示例(1)

- `flow-engine-examples/src/main/java/io/github/mchgood/flow/OrderExample.java`:
  5 个 lambda 节点 → 5 个 `@Component("checkStock")` 等命名的类(行为逐字节等价);
  `AnnotationConfigApplicationContext` 直接注册组件类;引擎/resolver 装配 @Bean 不变。

### 测试(4 类)

- `SpringResolverContractTest`:@Bean work() → @Component("work") 静态类;
  测非 singleton/作用域代理的 `registerBean` 用法保留(测试目标就是自定义作用域)。
- `GenericNodeIntegrationTest`:4 个 @Bean 节点 → @Component 静态类(显式命名)。
- `FlowEngineAutoConfigurationTest`:节点 lambda `withBean("echo",...)` → `@Component("echo")`
  静态类 + `withUserConfiguration`;AutoLoadNodes 的 check/pack @Bean → @Component 类。
  非 FlowNode 的 withBean(resolver/evaluator/config/engine/source)保留。
- `FlowSourceRegistrarTest`:AutoLoadNodes 同上处理。
- 明确不改:`FlowEngineTest`(map-resolver 纯运行时测试,无 Bean 注册含义);
  `SpelContractTest`(不含节点 Bean)。

## 4. 验证

- 代码/测试/示例变更 → `mvn verify`(全部 235 测试保持绿,行为零变化)+ `python3 scripts/check-coverage.py`
  (LINE ≥ 95%、BRANCH ≥ 88%;新增类全在 test/examples 模块,不影响生产聚合)。
- 文档:链接可达、示例与 @Component 形态一致、无残留误导性 @Bean 节点示例(grep 验证)。
- Checkstyle 随 verify 强制;测试内部类命名遵循现有风格。
