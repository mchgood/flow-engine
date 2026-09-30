# 阿里巴巴 Java 代码规范整改设计

- 日期：2026-09-30
- 范围：`flow-engine` 全部 50 个 Java 源文件（38 main + 12 test，约 3237 行）
- 性质：纯格式与命名整改，**不改变任何运行时行为**
- 基线：`mvn -B verify` BUILD SUCCESS，205 个测试全绿，LINE 96.03%（484/504），BRANCH 89.98%（575/639）

## 1. 问题现状

代码目前处于高度压缩风格，实测统计如下：

| 问题 | main | test | 合计 |
| --- | --- | --- | --- |
| 控制流缺失 `{}` | 87 | 10 | 97 |
| 一行多语句（`;` > 1） | 147 | 128 | 275 |
| 单行超过 120 字符 | 103 | 159 | 262 |
| 通配符 import | 19 处 / 9 文件 | 42 处 / 11 文件 | 61 处 / 20 文件 |
| 运算符与关键字后缺空格 | 普遍 | 普遍 | 全项目 |
| ≤2 字符简写标识符 | `e`×90 `n`×40 `r`×18 `d`×11 `c`×9 `a`×8 `s`×5 `b`×4 `l`×3 `p` `q` `w` `x` `t` `f` `g` `fs` `pf` `ex` `md` `an` | | |

问题最集中的文件：

| 文件 | 行数 | 超 120 字符 | 一行多语句 |
| --- | --- | --- | --- |
| `flow-engine-core/.../runtime/DefaultFlowEngine.java` | 439 | 45 | 85 |
| `flow-engine-spring/src/test/.../FlowEngineTest.java` | 216 | 75 | 64 |
| `flow-engine-core/.../internal/compiler/FlowCompiler.java` | 218 | 31 | 40 |
| `flow-engine-core/src/test/.../RuntimeBoundaryTest.java` | 146 | 23 | 28 |
| `flow-engine-core/src/test/.../contract/ValueContractTest.java` | 71 | 16 | 11 |
| `flow-engine-spring/.../SpelConditionEvaluator.java` | 99 | 14 | 6 |

单行最长记录为 `FlowEngineTest.java` 的 479 字符（整个 `try`/`for`/`finally` 挤在一行）。

## 2. 目标与非目标

### 2.1 目标

1. 全项目对齐阿里巴巴 Java 开发手册的**格式规约**与**命名规约**。
2. 引入 checkstyle 作为 `verify` 阶段闸门，防止风格回退。
3. 整改过程零行为变更，由既有 205 个测试与 token 级等价校验双重保障。

### 2.2 非目标

以下内容明确**不做**，避免范围蔓延：

- 不拆分方法、不改变控制流结构、不提取公共方法。
- 不把魔法值提取为常量（`EngineConfig.defaults()` 里的 `8, 128, 64, ...` 保持原样）。
- 不添加 `final`、不调整集合初始化容量。
- 不改动 Javadoc 的文字内容（仅在其所在行需要折行时按 R5 处理，或随形参改名同步更新 `@param` 标签名）。
- 不改动异常处理、日志、并发、事务语义。
- 不引入 PMD / P3C 规则集。
- 不改动 `docs/*.md` 与 `README.md` 中的技术描述；已核实文档内 13 个 `java` 代码块均已符合规范，无需改动。
- 不重命名任何类型名、方法名（`Decl`、`Parsed`、`Cursor`、`md(...)`、`config(...)`、`engine(...)` 等全部保留）。
- **例外（用户裁决，2026-09-30）**：补齐全部缺失的 `@Override`。阿里规约【强制】要求「所有覆写方法必须加 `@Override` 注解」，此项虽超出「格式+命名」字面边界，但 `@Override` 是无害注解、不改变字节码行为，且是原诉求「符合阿里规范」的一部分，故纳入范围。已核实全项目缺失 **11 处**：core 测试 5 处（`PackageBoundaryTest` 的匿名 `ConditionEvaluator` 2、`CompilerContractTest` 的匿名 `ConditionEvaluator` 1、`RuntimeBoundaryTest` 的匿名 `ConditionEvaluator` 2）、`SpelConditionEvaluator` 内部类 6 处（`ReadOnlyMapAccessor` 5、守卫 `AbstractMap` 的 `entrySet` 1）。除此之外仍不新增任何注解。
- 不启用 `EmptyLineSeparator`、`MissingJavadoc*`、`FinalLocalVariable`、`MagicNumber`、`AbbreviationAsWordInName`、`DeclarationOrder`（churn 过大或误报率高）。

## 3. 整改规则清单

| 编号 | 规则 | 手册依据 | 处理方式 |
| --- | --- | --- | --- |
| R1 | `if` / `else` / `for` / `while` / `do` 必须带 `{}`，即使只有一条语句 | 【强制】 | 97 处全部补全 |
| R2 | 一行只放一条语句 | 【强制】 | 275 处拆行 |
| R3 | 一行只声明一个变量，如 `final String id, label, target;` | 【强制】 | 逐条拆分为独立声明 |
| R4 | 关键字后、运算符两侧、逗号后必须有空格 | 【强制】 | 全量补齐 |
| R5 | 单行 ≤ 120 字符，超出按手册折行规则换行 | 【强制】 | 262 处折行 |
| R6 | 禁用通配符 import（含静态通配符） | 【强制】 | 61 处展开为具体类型 |
| R7 | import 分组有序：`io.github.mchgood` → `org` → `java` → `javax`，静态 import 置底 | 【推荐】 | 全量重排（已实测项目只出现这 4 个顶级前缀，无 `com`/`jakarta`） |
| R8 | 注解独占一行，如 `@Override public void run(){` 拆为两行 | 【推荐】 | 全量调整 |
| R9 | 4 空格缩进、禁用 Tab、文件末尾保留换行、左括号行尾 / 右括号规则 | 【强制】 | 全量 |
| R10 | 长整型字面量使用大写 `L`；修饰符顺序符合 JLS；数组声明用 `String[] args` 形式 | 【强制】 | 全量扫描 |
| R11 | 简写标识符语义化，禁止无意义单字母与拼音缩写 | 【强制】 | 见第 4 节术语表 |
| R13 | 覆写方法必须加 `@Override` 注解，且注解独占一行 | 【强制】 | 补齐 11 处缺失覆写注解（见 §2.2 例外条款） |
| R12 | 代码中不得出现内联全限定类名，一律改为 import | 【推荐】 | 已实测 4 处：`ValueContractTest` 的 `java.util.stream.IntStream` ×2、`FlowCompiler` 的 `java.security.NoSuchAlgorithmException`、`GenericNodeIntegrationTest` 的 `org.aopalliance.intercept.MethodInterceptor` |

### 3.1 R5 折行的特殊约定

对齐手册原文「运算符与下文一起换行；方法调用的点符号与上文一起换行；多个参数需换行时在逗号后进行」：

- 二元运算符换到**下一行开头**（`OperatorWrap` 取 `nl`）。
- 点符号留在**上一行末尾**（`SeparatorWrap` 对 `.` 取 `eol`），逗号同样留在上一行末尾（`eol`）。
- 第二行相对第一行缩进 4 空格（`lineWrappingIndentation=4`）。
- 长字符串字面量按语义边界（Mermaid 的 `\n`）用 `+` 拼接折行，**不改变字符串内容**。

## 4. 命名术语表

统一原则：**所有 ≤2 字符标识符一律语义化**。白名单仅 5 个：

- 循环下标 `i`、`j`、`k`
- 领域词 `id`（节点/流程标识）、`to`（图论端点，与已保留的 `from` 配对）

lambda 参数、catch 参数、模式匹配变量、record 组件**均适用**同一白名单，无额外豁免。

### 4.1 `flow-engine-core/runtime/DefaultFlowEngine.java`

| 现名 | 新名 | 说明 |
| --- | --- | --- |
| `WORKER` | `CURRENT_ENGINE` | 实为 `ThreadLocal<DefaultFlowEngine>`，现名易误解为工作线程 |
| `pool` | `workerPool` | |
| `permits` | `admissionPermits` | 根调用准入额度 |
| `r` | `root` | `Root` 形参 |
| `e` | `execution` | `Execution` |
| `n` | `node` | `RuntimeNode` |
| `d` | `definition` | `Definition` |
| `p` | `parent` | `Execution` 父引用 |
| `a` | `ancestor` | `RuntimeNode` 祖先 |
| `x` | `candidate` | `Execution` 遍历候选 |
| `ex` | `rejection` | `RejectedExecutionException` |
| `f` | `flowException` | `instanceof FlowException` 模式变量 |
| `w` | `work` | `instanceof Work` 模式变量 |
| lambda `r` / `t` | `runnable` / `thread` | 线程工厂 lambda |

### 4.2 `flow-engine-core/internal/graph/Definition.java` 与 `internal/compiler/MutableGraph.java`

| 现名 | 新名 | 说明 |
| --- | --- | --- |
| `Definition.Node.in` | `Definition.Node.incomingEdges` | `public final`，但该类 Javadoc 已声明「不是宿主应直接依赖的稳定 API」，且项目为 `0.1.0-SNAPSHOT` 从未发布 |
| `Definition.Node.out` | `Definition.Node.outgoingEdges` | 同上 |
| `MutableGraph.Node.in` | `MutableGraph.Node.incomingEdges` | 包内字段 |
| `MutableGraph.Node.out` | `MutableGraph.Node.outgoingEdges` | 包内字段 |
| `Edge.from` / `Edge.to`、`EdgeSpec.from/to`、`NodeSpec.from/to` | **保留** | 图论标准术语，非缩写；`to` 进白名单 |

### 4.3 `flow-engine-core/internal/compiler/FlowCompiler.java`

| 现名 | 新名 |
| --- | --- |
| `c`（`Cursor`） | `cursor` |
| `b`（`FencedCodeBlock`） | `block` |
| `l`（`int` 行下标） | `lineIndex` |
| `p`（`Cursor` 内 `int` 位置） | `position` |
| `a`（`Cursor` 内 `int` 起点） | `start` |
| `d`（`Decl`） | `declaration` |
| `e`（`Edge`） | `edge` |
| `n`（`Node`） | `node` |
| `s`（`Set`） | `shared` |
| `q`（`Deque`） | `queue` |
| `Decl.loc` | `location` |

> `loc` 为 3 字符，不落在「≤2 字符」硬规则内，但同一编译链路里 `MutableGraph.Node`、`Definition.NodeSpec`、SPI `ConditionEvaluator.parse` 均已使用 `location`。为消除同义词不一致，一并改名；`Decl` 是 `FlowCompiler` 的私有 record，无外部影响。`CompilerContractTest` 中匿名实现的同名形参 `loc` 同步改为 `location`。

### 4.4 `flow-engine-spring/SpelConditionEvaluator.java`

| 现名 | 新名 |
| --- | --- |
| `ALLOWED` | `ALLOWED_NODE_KINDS` |
| `c`（`EvaluationContext`） | `context` |
| `n`（`NodeRecord`） | `record` |
| `out`（`Map`） | `view` |
| catch `e` ×2 | `flowException` / `runtimeException` |

### 4.5 其余 main 文件

| 文件 | 现名 → 新名 |
| --- | --- |
| `config/EngineConfig.java` | `d`（`Duration`） → `timeout` |
| `node/NodeContext.java` | `r` → `record` |

### 4.6 测试代码

| 现名 | 新名 |
| --- | --- |
| `e`（引擎） | `engine` |
| `r`（结果） | `result` |
| `c`（节点上下文） | `context` |
| `g`（编译产物） | `definition` |
| `n`（Bean） | `node` |
| `fs` | `futures` |
| `ex` / `x`（catch） | `exception` |
| `a` / `b`（`Future`） | `first` / `second` |
| `md`（变量） | `markdown` |
| `pf` / `an` / `is` / `or` | 实施时按运行时类型逐个确认后语义化 |

术语表未覆盖的短名，一律**按其运行时类型语义化**，并在实施时逐个确认。

## 5. checkstyle 闸门

### 5.1 布局与插件

- 配置文件：`config/checkstyle/checkstyle.xml`（仓库根）。
- 插件：`org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0` + `com.puppycrawl.tools:checkstyle:10.21.4`（已实测可从项目镜像解析）。
- 声明位置：root `pom.xml` 的 `<build><plugins>`，对所有子模块生效。
- 绑定：execution id `checkstyle-check`，goal `check`，phase **`verify`**。
- CI：`.github/workflows/ci.yml` 已执行 `mvn -B verify`，闸门自动生效，**workflow 无需改动**。

### 5.2 插件配置

```
configLocation          = ${maven.multiModuleProjectDirectory}/config/checkstyle/checkstyle.xml
includeTestSourceDirectory = true
failOnViolation         = true
violationSeverity       = warning
consoleOutput           = true
linkXRef                = false
```

`maven.multiModuleProjectDirectory` 由 Maven 3.3.1+ 启动器注入，本机 Maven 3.8.1 与 CI 的 `setup-java` 均满足。

### 5.3 规则集（约 42 条，仅格式与命名两类）

**Checker 级**

| 模块 | 关键配置 |
| --- | --- |
| `FileTabCharacter` | `eachLine=true` |
| `NewlineAtEndOfFile` | `lineSeparator=lf` |
| `LineLength` | `max=120`，`ignorePattern=^(package\|import) .*\|.*\bhttps?://\S*` |

**TreeWalker — Import**

| 模块 | 关键配置 |
| --- | --- |
| `AvoidStarImport` | `allowClassImports=false`，`allowStaticMemberImports=false` |
| `UnusedImports` / `RedundantImport` / `IllegalImport` | 默认 |
| `ImportOrder` | `option=bottom`，`groups=io.github.mchgood,org,com,java,javax`，`separated=true`，`ordered=true`，`sortStaticImportsAlphabetically=true` |

**TreeWalker — 大括号与块**

| 模块 | 关键配置 |
| --- | --- |
| `NeedBraces` | 默认 token（`LITERAL_DO/ELSE/FOR/IF/WHILE`），`allowSingleLineStatement=false` |
| `LeftCurly` | `option=eol` |
| `RightCurly` | `option=same`：`LITERAL_TRY/CATCH/FINALLY/IF/ELSE/DO`；`option=alone_or_singleline`：`CLASS_DEF/METHOD_DEF/CTOR_DEF/LITERAL_FOR/LITERAL_WHILE/STATIC_INIT/INSTANCE_INIT/RECORD_DEF` |
| `EmptyBlock` | `option=text` |
| `EmptyCatchBlock` | `exceptionVariableName=ignored` |
| `EmptyStatement` / `AvoidNestedBlocks` | 默认 |

> `alone_or_singleline` 用于容纳 `private record Parsed(...) implements CompiledCondition {}` 这类单行空体，避免与 R3/R9 冲突。

**TreeWalker — 空白**

`WhitespaceAround`（`allowEmptyTypes=true`、`allowEmptyConstructors=true`、`allowEmptyMethods=true`、`allowEmptyLambdas=true`、`allowEmptyCatches=true`）、`WhitespaceAfter`、`NoWhitespaceBefore`、`NoWhitespaceAfter`、`GenericWhitespace`、`MethodParamPad`、`ParenPad`、`TypecastParenPad`、`SingleSpaceSeparator`、`NoLineWrap`、`EmptyForInitializerPad`、`EmptyForIteratorPad`。

**TreeWalker — 语句与声明**

`OneStatementPerLine`、`MultipleVariableDeclarations`、`OneTopLevelClass`、`OuterTypeFilename`。

**TreeWalker — 折行与缩进**

`Indentation`（`basicOffset=4`、`caseIndent=4`、`throwsIndent=8`、`arrayInitIndent=4`、`lineWrappingIndentation=4`、`forceStrictCondition=false`）、`OperatorWrap`（`option=nl`）、`SeparatorWrap`（`,` 与 `.` 均取 `option=eol`，见 §3.1）。

**TreeWalker — 注解与修饰符**

`AnnotationLocation`（**三个 `allowSameline*` 属性全部置 `false`**：`allowSamelineSingleParameterlessAnnotation`、`allowSamelineParameterizedAnnotation`、`allowSamelineMultipleAnnotations`；否则 `@Override public void run()` 不会被拦。注意 10.21.4 **没有** `allowSamelineSingleAnnotations` 这个属性，写错会导致整个 TreeWalker 初始化失败）、`ModifierOrder`、`ArrayTypeStyle`、`UpperEll`。

**TreeWalker — 命名（防回归核心）**

| 模块 | `format` |
| --- | --- |
| `PackageName` | `^[a-z]+(\.[a-z][a-z0-9]*)*$` |
| `TypeName` | `^[A-Z][a-zA-Z0-9]*$` |
| `MethodName` | `^[a-z][a-zA-Z0-9]*$` |
| `MemberName` / `StaticVariableName` | `^(?:id\|to\|[a-z][a-zA-Z0-9]{2,})$` |
| `ParameterName` / `RecordComponentName` / `CatchParameterName` / `PatternVariableName` | `^(?:id\|to\|[a-z][a-zA-Z0-9]{2,})$` |
| `LocalVariableName` / `LocalFinalVariableName` / `LambdaParameterName` | `^(?:i\|j\|k\|id\|to\|[a-z][a-zA-Z0-9]{2,})$` |
| `ConstantName` | `^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$\|^LOG$` |

四类 `format` 共用同一份白名单，与第 4 节术语表逐字一致，不为个别字段开口子。

### 5.4 白名单为何是 `i`/`j`/`k`/`id`/`to`

| 白名单词 | 出现位置 | 不改的理由 |
| --- | --- | --- |
| `i` / `j` / `k` | `for` 循环下标、数组下标 | 手册认可的惯用下标名；仅限 `LocalVariableName` 与 `LambdaParameterName`，**不进** `MemberName`/`ParameterName` |
| `id` | `Definition.id`、`Node.id`、`Edge.id`、`MutableGraph.Node.id`、`NodeSpec.id`、`FlowException` 相关形参 | 领域通用词，改为 `identifier` 会波及 public API 并与 record 组件产生不一致 |
| `to` | `Edge.to`、`EdgeSpec.to`、`NodeSpec.to` | 与已保留的 `from` 成对的图论端点术语；单独改 `to` 会破坏对称性 |

`MemberName` 与 `ParameterName` 不含 `i`/`j`/`k`，因此字段和形参仍然强制 ≥3 字符（`id`/`to` 除外）。

### 5.5 已知风险与处置

| 风险 | 处置 |
| --- | --- |
| `Indentation` 对 fluent 链式调用与折行条件误报 | 保持 `forceStrictCondition=false`（默认容忍额外缩进）；若仍有无法通过纯排版消除的误报，仅在该 module 上方加注释记录误报形态，不删除 module、不放宽其他规则 |
| `ImportOrder` 分组与既有松散分组不符导致大量重排 | 属预期改动（R7），一次性重排后由闸门维持 |
| `LineLength` 对超长字符串字面量无解 | 仅 8 处，见 §6.3 白名单，用 `+` 拼接折行 |
| `EmptyBlock` / `EmptyCatchBlock` 拦到空体 | 已实测：全项目空 `{}` 只出现在 record / interface / 匿名类 / 静态嵌套类的**类型体**上（17 处），而 `EmptyBlock` 默认 token 只覆盖控制流块，不会误伤；唯一的空 catch 是 `FlowEngineTest.java:152` 的 `catch (InterruptedException ignored) {}`，变量名已与 `EmptyCatchBlock` 的 `exceptionVariableName=ignored` 一致 |
| `RightCurly` 拦到单行空类型体（`record Parsed(...) {}`、`new CompiledCondition() {}`） | 对 `CLASS_DEF`/`RECORD_DEF`/`METHOD_DEF`/`CTOR_DEF` 等取 `alone_or_singleline`，允许整块单行；`OBJBLOCK` 同规则处理 |
| `AnnotationLocation` 拦到参数级注解 | 已实测项目内参数级注解为零（`(@Foo ...)` 形态不存在），`allowSamelineParameters` 保持默认 |
| checkstyle 模块名或属性名写错导致 `TreeWalker` 整体初始化失败 | 已实测两个易错点：正确模块名是 `OneTopLevelClass`（**不是** `OneTopLevelClassDeclaration`）；`AnnotationLocation` 在 10.21.4 里没有 `allowSamelineSingleAnnotations`，正确属性是 `allowSamelineSingleParameterlessAnnotation` / `allowSamelineParameterizedAnnotation` / `allowSamelineMultipleAnnotations` |
| checkstyle 10.21.4 在本机 JDK 25 / CI JDK 17 双版本运行 | 已实测 JDK 25 下配置可加载并报告违规；checkstyle 10.x 完整支持 Java 17 语法（record、sealed、模式匹配），CI 侧无风险 |

### 5.6 闸门实测结果

用未整改的 `config/EngineConfig.java`（45 行，含 3 行超长、2 处一行多语句、4 处缺大括号、`for(var d:...)` 短名）作输入，实测 `maven-checkstyle-plugin:3.6.0` + `checkstyle:10.21.4` 加载本规则集后报出 **96 条违规**：

| 规则 | 条数 | 对应规约 |
| --- | --- | --- |
| `WhitespaceAround` | 55 | R4 |
| `WhitespaceAfter` | 28 | R4 |
| `NeedBraces` | 4 | R1 |
| `LineLength` | 3 | R5 |
| `LeftCurly` | 2 | R9 |
| `AvoidStarImport` | 1 | R6 |
| `OneStatementPerLine` | 1 | R2 |
| `ParameterName` | 1 | R11 |
| `LocalVariableName` | 1 | R11 |

违规类型与 §3 的规则清单逐条对应，说明规则集既能拦住目标问题、也没有引入无关噪声。

## 6. 执行顺序与验证

### 6.1 执行顺序

按模块与包依赖顺序推进，checkstyle 配置放在最后：

1. `flow-engine-core` main：`api` → `spi` → `config` → `result` → `exception` → `node` → `internal.graph` → `internal.compiler` → `runtime`
2. `flow-engine-core` test
3. `flow-engine-spring` main
4. `flow-engine-spring` test
5. `flow-engine-spring-boot-starter` + `flow-engine-examples`（main + test）
6. 新增 `config/checkstyle/checkstyle.xml` 与 root pom 插件绑定，跑 `mvn verify` 调平剩余违规

每步结束执行 `mvn -q -pl <module> -am test`；第 6 步结束执行 `mvn -B verify` 与 `python3 scripts/check-coverage.py`。

**checkstyle 放最后的理由**：先让代码干净，再落规则，避免边写边被闸门打断、反复调阈值。

### 6.2 提交粒度

按模块与包分次提交，共 9 个 commit，每个都必须编译 + 测试通过，便于逐个 review 与二分回退：

1. `refactor: rename graph adjacency fields to incomingEdges and outgoingEdges`
2. `style: reformat flow-engine-core contract packages per Alibaba conventions`
3. `style: reformat flow-engine-core internal packages per Alibaba conventions`
4. `style: reformat flow-engine-core runtime per Alibaba conventions`
5. `style: reformat flow-engine-core tests per Alibaba conventions`
6. `style: reformat flow-engine-spring sources per Alibaba conventions`
7. `style: reformat flow-engine-spring tests per Alibaba conventions`
8. `style: reformat starter and examples per Alibaba conventions`
9. `build: enforce Alibaba style rules with checkstyle gate`

commit 1 单独存在的原因：`in`/`out` → `incomingEdges`/`outgoingEdges` 横跨 `internal.graph`、`internal.compiler`、`runtime` 三个包以及 core 的测试，必须原子完成才能编译。把它与格式化混在同一个 commit 会让 review 无法区分「改名」与「排版」。core main 也按 `api/spi/exception/result/config/node` → `internal` → `runtime` 拆成 3 个 commit，因为 `runtime` 的 `DefaultFlowEngine` 单文件 439 行、85 处一行多语句，独立成一个 review 单元更合适。

文档改动范围：整改过程**只**新增 `docs/superpowers/**`，不改动 `docs/requirements.md`、`docs/technical-design.md`、`docs/quick-start.md`、`docs/spring-boot.md`、`docs/testing-coverage.md`、`README.md`（已核实这些文档内 13 个 `java` 代码块均已合规，且未引用任何被重命名的符号）。commit 9 额外在 `AGENTS.md` 的 Testing expectations 段落追加一段闸门说明；`.github/workflows/ci.yml` 无需改动。

### 6.3 语义等价硬校验

测试之外增加第二道保险。**每个模块整改完成、提交之前**，对该模块所有改动文件逐一比对 `git show <上一个 commit>:<path>` 与工作区版本。规范化顺序如下（顺序很重要：标识符擦除依赖词边界，必须在删除空白之前完成）：

1. 剔除 `package` 行、`import` 行、行注释与块注释（R6/R7 会改变 import 内容）。
2. 把字符串字面量替换为 `STR`，字符字面量替换为 `CHR`。
3. 把术语表**词表**（全部旧名的基础形 + 全部新名，共 97 个标识符）里的标识符在两侧同时替换为占位符 `NAME`。
4. 删除所有 `{` 与 `}`（R1 会插入成对大括号），再删除所有空白字符。
5. 断言两侧 token 流逐字符相同。

任何差异都意味着改到了语义，必须人工确认后才能提交。校验脚本置于 `/tmp`，不入库。

**为什么用「词表擦除」而不是「旧名 → 新名」正向替换**：同一个旧名在不同作用域会映射到不同新名（`DefaultFlowEngine` 里 `e` 既是 `Execution` 又是 `InterruptedException`，`FlowCompiler` 里 `n` 既是一般节点又是 `walk` 的当前节点），扁平的整词正则无法区分作用域，正向替换必然出错。擦除策略让比较对象变成「结构、字面量、数值、方法名、调用顺序」，与改名彻底解耦。

已实测验证：

- 对 `EngineConfig.java` 施加合法改动（补空格 + 拆行 + `d` → `timeout`）→ `failed=0`，且 `mvn compile` 通过；
- 对同文件把 `maxSubflowDepth>32` 改成 `>64` → `EQUIV FAIL`，精确定位到分歧字符与上下文，退出码 1。

> 本校验**不能**发现两类缺陷，需由其他手段兜底：漏掉一个必要大括号（由 `mvn test` 与 checkstyle 的 `NeedBraces` 兜底）、改错名（由编译器、测试与 checkstyle 的命名 `format` 兜底）。它的职责是捕捉意外删改语句、改动阈值与字面量、调整调用顺序。

**合法差异白名单（8 处，全在 2 个测试文件）**：Mermaid 定义字符串字面量长度 105–277 字符，超 120 列，需按 `\n` 边界用 `+` 拼接折行。

| 文件 | 行号（整改前） | 字面量长度 |
| --- | --- | --- |
| `flow-engine-core/src/test/.../RuntimeBoundaryTest.java` | 125 | 277 |
| `flow-engine-core/src/test/.../RuntimeBoundaryTest.java` | 140 | 158 |
| `flow-engine-core/src/test/.../RuntimeBoundaryTest.java` | 76 | 122 |
| `flow-engine-spring/src/test/.../FlowEngineTest.java` | 178 | 269 |
| `flow-engine-spring/src/test/.../FlowEngineTest.java` | 50 | 142 |
| `flow-engine-spring/src/test/.../FlowEngineTest.java` | 103 | 125 |
| `flow-engine-spring/src/test/.../FlowEngineTest.java` | 57 | 123 |
| `flow-engine-spring/src/test/.../FlowEngineTest.java` | 41 | 105 |

这 8 处在 token 校验中单独豁免，并逐处人工复核拼接后的字符串与原字面量逐字节相同。

### 6.4 覆盖率影响

拆行会增加 JaCoCo 的行数分母，但被覆盖行同比例增加，预期 LINE / BRANCH 覆盖率基本持平。

验收门槛（不得下调，AGENTS.md 明令）：

- `mvn -B verify` BUILD SUCCESS
- 测试数保持 205，失败 0
- `python3 scripts/check-coverage.py`：LINE ≥ 95%，BRANCH ≥ 88%

若覆盖率跌破门槛，视为改写引入了代码路径变化的**缺陷**，必须定位并修复，禁止调整阈值。

### 6.5 验收清单

- [ ] 50 个文件全部通过 §5.3 规则集，`mvn verify` 中 checkstyle 零违规
- [ ] `mvn -B verify` BUILD SUCCESS，205 测试全绿
- [ ] `python3 scripts/check-coverage.py` 通过，LINE ≥ 95%、BRANCH ≥ 88%
- [ ] §6.3 token 等价校验对 50 个文件全部通过（8 处白名单已人工复核）
- [ ] 全项目 `grep` 无 `import .*\*;`
- [ ] 全项目 `@Override` 相比基线新增恰好 11 处，且均独占一行
- [ ] 全项目单行长度 ≤ 120
- [ ] 术语表内所有旧名在 `src/` 下零残留
- [ ] 6 个 commit 按 §6.2 划分，每个 commit 独立可编译可测试
