# FlowNode 示例改 @Component 形式实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 文档、示例、单元测试中所有 FlowNode 定义从 `@Bean`/lambda 形式改为 `@Component` 命名类,避免误导用户;生产代码零变更。

**Architecture:** `SpringNodeResolver` 按 `factory.getBean(id, FlowNode.class)` 名称查找,注册方式无关。转换只动文档/示例/测试;非 FlowNode Bean(engine/resolver/evaluator/config/source)保留 @Bean。spec: `docs/superpowers/specs/2026-10-01-component-style-nodes-design.md`。

**Tech Stack:** Spring 组件注册(注解类可直接经 `withUserConfiguration`/`AnnotationConfigApplicationContext` 注册);docs-only 验证 + `mvn verify` + `python3 scripts/check-coverage.py`。

## Global Constraints

- 生产代码(main 源集,含 starter/spring/core)零变更;只动 test、examples、docs、README。
- 节点类行为与原 lambda 逐字节等价(返回值/异常路径不变);Bean 名 = 原节点 ID,流程引用不变。
- 文档示例优先展示"类名首字母小写即节点 ID"的默认命名(如 `ValidateOrder` → `validateOrder`);测试类用显式 `@Component("check")` 展示另一种方式;两处都补一句"lambda/动态场景仍可用 @Bean"。
- 每个新命名类(含测试内嵌套类)按 AGENTS.md 要求写中文 Javadoc。
- Checkstyle 随 verify 强制;测试总数应保持 235 不变(转换不增删用例)。
- 提交在 main(用户已裁定)。

---

### Task 1: starter 测试改 @Component 节点

**Files:**
- Modify: `flow-engine-spring-boot-starter/src/test/java/io/github/mchgood/flow/boot/autoconfigure/FlowEngineAutoConfigurationTest.java`
- Modify: `flow-engine-spring-boot-starter/src/test/java/io/github/mchgood/flow/boot/flows/FlowSourceRegistrarTest.java`

**Interfaces:**
- Consumes: 现有测试结构与断言(不改任何断言)
- Produces: `@Component("echo")`/`@Component("check")`/`@Component("pack")` 静态节点类;非 FlowNode 的 withBean 全部保留

- [ ] **Step 1: 转换 FlowEngineAutoConfigurationTest**

1. 新增静态节点类(替换原 `AutoLoadNodes` 配置类中的两个 @Bean 与各测试的 `withBean("echo", FlowNode.class, () -> context -> context.input())`):

```java
    /**
     * 自动加载与装配测试共用的 echo 回显节点。
     */
    @Component("echo")
    static class EchoNode implements FlowNode<Object> {
        @Override
        public Object execute(NodeContext context) {
            return context.input();
        }
    }

    /**
     * 自动加载测试资源所需的 check 节点。
     */
    @Component("check")
    static class CheckNode implements FlowNode<Object> {
        @Override
        public Object execute(NodeContext context) {
            return context.input();
        }
    }

    /**
     * 自动加载测试资源所需的 pack 节点。
     */
    @Component("pack")
    static class PackNode implements FlowNode<Object> {
        @Override
        public Object execute(NodeContext context) {
            return context.input();
        }
    }
```

2. runner 字段:`.withUserConfiguration(AutoLoadNodes.class)` → `.withUserConfiguration(CheckNode.class, PackNode.class)`;删除 AutoLoadNodes 类。
3. 各测试 `withBean("echo", FlowNode.class, () -> context -> context.input())` → `withUserConfiguration(EchoNode.class)`(链式位置保持语义等价)。
4. `BootHost` 的 `@Import(AutoLoadNodes.class)` → `@Import({CheckNode.class, PackNode.class})`。
5. import 调整:去掉不再使用的 `Bean`;新增 `io.github.mchgood.flow.node.NodeContext`、`org.springframework.stereotype.Component`。
6. **保留**:所有 NodeResolver/ConditionEvaluator/EngineConfig/FlowEngine 的 withBean/lambda;`customEngineIsNotDuplicated` 等测试的 lambda resolver。

- [ ] **Step 2: 转换 FlowSourceRegistrarTest**

同样把 `AutoLoadNodes` 的 check/pack @Bean 换成 `@Component("check")`/`@Component("pack")` 静态类(与 Step 1 相同实现),runner 字段改 `withUserConfiguration(CheckNode.class, PackNode.class)`;测试内 `withBean("check", ...)` 如有则同样替换;TestSource、非 FlowNode 注册保留。

- [ ] **Step 3: 运行验证**

Run: `mvn -q -pl flow-engine-spring-boot-starter -am test`
Expected: BUILD SUCCESS;starter 测试数与基线一致(52);无断言改动。

- [ ] **Step 4: Commit**

```bash
git add flow-engine-spring-boot-starter/src/test
git commit -m "test(boot): register flow nodes as component classes"
```

---

### Task 2: spring 模块测试改 @Component 节点

**Files:**
- Modify: `flow-engine-spring/src/test/java/io/github/mchgood/flow/spring/SpringResolverContractTest.java`
- Modify: `flow-engine-spring/src/test/java/io/github/mchgood/flow/spring/GenericNodeIntegrationTest.java`

**Interfaces:**
- Consumes: 现有断言(不改)
- Produces: `@Component("work")`、`@Component("validate")` 等静态节点类

- [ ] **Step 1: SpringResolverContractTest**

`@Bean FlowNode<?> work()` → `@Component("work")` 静态类(行为等价);**保留**测非 singleton/作用域代理的 `ctx.registerBean("work", FlowNode.class, () -> context -> 1)` 用法与自定义 scope 注册(那是测试目标)。import 增加 `org.springframework.stereotype.Component`。

- [ ] **Step 2: GenericNodeIntegrationTest**

4 个 `@Bean` 节点(validate/describe/complete/wrongType,带泛型 `FlowNode<ValidationResult>` 等)→ 4 个 `@Component("validate")` 等显式命名的静态类,泛型与行为逐字节等价;配置类中其余 Bean(如引擎)保留。

- [ ] **Step 3: 运行验证**

Run: `mvn -q -pl flow-engine-spring -am test`
Expected: BUILD SUCCESS;spring 测试数与基线一致(84)。

- [ ] **Step 4: Commit**

```bash
git add flow-engine-spring/src/test
git commit -m "test(spring): register flow nodes as component classes"
```

---

### Task 3: OrderExample 改 @Component 节点

**Files:**
- Modify: `flow-engine-examples/src/main/java/io/github/mchgood/flow/OrderExample.java`

**Interfaces:**
- Consumes: 现有 5 个 lambda 节点(checkStock/reserveStock/calculatePrice/recordReview/saveOrder,以实际文件为准)与流程图常量
- Produces: 5 个 `@Component("节点ID")` 命名类;`AnnotationConfigApplicationContext` 直接注册组件类;`FlowEngine`/resolver 装配 @Bean 保留

- [ ] **Step 1: 转换**

每个 lambda 节点 → 命名类,示例模板(逻辑从原 lambda 原样搬运):

```java
    /**
     * 校验订单金额与库存入参。
     */
    @Component("validateOrder")
    static class ValidateOrderNode implements FlowNode<Map<String, Object>> {
        @Override
        public Map<String, Object> execute(NodeContext context) {
            // 原 validateOrder lambda 的实现,逐行等价
        }
    }
```

上下文装配:原 `AnnotationConfigApplicationContext(Config.class)` 风格改为同时注册各节点类
(如 `new AnnotationConfigApplicationContext(OrderConfig.class, ValidateOrderNode.class, ...)`,
以实际文件结构为准);`@Bean(destroyMethod = "close") FlowEngine` 与 resolver/evaluator 装配不变。

- [ ] **Step 2: 运行验证**

Run: `mvn -q -pl flow-engine-examples -am test`
Expected: BUILD SUCCESS;OrderExampleTest 断言不变全绿。

- [ ] **Step 3: Commit**

```bash
git add flow-engine-examples/src
git commit -m "examples: register flow nodes as component classes"
```

---

### Task 4: 文档改 @Component 形式

**Files:**
- Modify: `README.md`
- Modify: `docs/quick-start.md`
- Modify: `docs/spring-boot.md`
- Modify: `docs/tutorial.md`

**Interfaces:**
- Consumes: Task 1-3 的最终形态;现有文档结构(不改章节骨架)
- Produces: 所有 FlowNode 示例为 @Component 类形式 + 命名规则说明 + @Bean 兼容一句

- [ ] **Step 1: README.md**

`@Bean FlowNode<Map<String, Object>> validateOrder()` 片段改为:

```java
@Component
public class ValidateOrder implements FlowNode<Map<String, Object>> {
    @Override
    public Map<String, Object> execute(NodeContext context) {
        // 原示例逻辑保持不变
    }
}
```

并在片段后补一句:类名首字母小写即节点 ID(`ValidateOrder` → `validateOrder`);不一致时用
`@Component("节点ID")` 显式命名;lambda 或动态注册仍可用 `@Bean` 方法。

- [ ] **Step 2: docs/quick-start.md**

5 个节点 @Bean → 5 个 `@Component` 类(类名 ValidateOrder/ReserveStock/CalculatePrice/
RecordReview/SaveOrder,默认命名恰好成立;逻辑原样搬运,import 齐全);`FlowEngine` 手动装配
@Bean 保留;第 33 行说明补:"类名首字母小写即 Bean 名,须与图中节点 ID 一致,否则显式
`@Component("节点ID")`"。

- [ ] **Step 3: docs/spring-boot.md**

DemoApplication 的 `@Bean FlowNode<String> greet()` → `@Component public class Greet ...`
(默认命名 greet 恰好成立);其余装配描述不变。

- [ ] **Step 4: docs/tutorial.md**

- 第 2 章:两个节点示例 → @Component 类(默认命名展示 + 一处显式命名对比);
- 第 2/4 章补命名规则("类名首字母小写即节点 ID;不一致显式命名;lambda 仍可用 @Bean");
- 第 3 章 Boot 路径如含节点 @Bean 一并转换;
- 第 6 章全景表的"覆盖方式"列核对仍准确(NodeResolver 等为 @Bean,FlowNode 为 @Component,表述更新);
- 第 7 章自定义 NodeResolver 的行文与新默认形态一致(不再把 @Bean 描述为节点唯一方式)。

- [ ] **Step 5: 一致性自检**

Run: `grep -rn "@Bean" README.md docs/quick-start.md docs/spring-boot.md docs/tutorial.md | grep -in "FlowNode"`
Expected: 无 FlowNode @Bean 残留(engine/resolver/evaluator/config/source 的 @Bean 不受影响)。
Run: 链接检查(各文件相对链接可达)。

- [ ] **Step 6: Commit**

```bash
git add README.md docs/
git commit -m "docs: present flow nodes as component classes"
```

---

### Task 5: 全量验证

**Files:** 无新增(修复除外)

- [ ] **Step 1: 门禁**

Run: `mvn verify`
Expected: BUILD SUCCESS;235 tests 全绿;0 checkstyle 违例。
Run: `python3 scripts/check-coverage.py`
Expected: LINE ≥ 95%、BRANCH ≥ 88%。

- [ ] **Step 2: 范围核对**

Run: `git diff --stat 5a942be..HEAD -- flow-engine-core flow-engine-spring/src/main flow-engine-spring-boot-starter/src/main`
Expected: 空输出(生产代码零变更)。

- [ ] **Step 3: 收尾报告**

向控制器报告:实际变更、运行的检查、遗留风险(如文档中保留的 @Bean 兼容说明)。
