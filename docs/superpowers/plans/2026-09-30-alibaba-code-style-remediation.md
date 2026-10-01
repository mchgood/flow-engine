# 阿里巴巴 Java 代码规范整改 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `flow-engine` 全部 50 个 Java 源文件从当前的压缩风格整改为符合阿里巴巴 Java 开发手册格式与命名规约的代码，并用 checkstyle 闸门锁定成果。

**Architecture:** 纯格式与命名整改，零行为变更。先建立 token 级语义等价校验脚手架，再按「跨文件字段改名 → 逐包格式化 → 逐模块测试 → 落闸门」顺序推进，每个 commit 独立可编译可测试。

**Tech Stack:** Java 17、Maven 3.8.1（本机 JDK 25 / CI JDK 17）、JUnit 5.11.4、Spring 7.0.9、Spring Boot 4.1.1、JaCoCo 0.8.13、maven-checkstyle-plugin 3.6.0 + checkstyle 10.21.4。

**规格文档：** `docs/superpowers/specs/2026-09-30-alibaba-code-style-remediation-design.md`

## Global Constraints

- 基线（不得回退）：`mvn -B verify` BUILD SUCCESS；测试 **205** 个、失败 **0**；`python3 scripts/check-coverage.py` 输出 **LINE >= 95%**（基线 96.03%，484/504）、**BRANCH >= 88%**（基线 89.98%，575/639）。
- 覆盖率跌破门槛一律视为**改写引入的缺陷**，必须定位修复；**禁止下调阈值**（AGENTS.md 明令）。
- 命名白名单**仅 5 个**：`i`、`j`、`k`（限局部变量与 lambda 参数）、`id`、`to`。其余 <=2 字符标识符一律语义化。
- 不重命名任何**类型名**与**方法名**（`Decl`、`Parsed`、`Cursor`、`md(...)`、`config(...)`、`engine(...)`、`record(...)` 等全部保留）。
- 不拆方法、不改控制流结构、不提魔法值为常量、不加 `final`、不改异常/日志/并发语义。
- Javadoc 只随形参改名同步更新 `@param` 标签名（如 `loc` -> `location`），其余**文字内容**一律不动。
- 按阿里规约【强制】条款补齐全部 **11 处** 缺失的 `@Override`（覆写方法必须标注）：core 测试 5 处（`PackageBoundaryTest` 2、`CompilerContractTest` 1、`RuntimeBoundaryTest` 2）+ `SpelConditionEvaluator` 内部类 6 处（`ReadOnlyMapAccessor` 5、守卫 `Map` 1）。除此之外**不新增**任何注解。
- 单行 <= **120** 字符；缩进 **4** 空格；禁 Tab；文件末尾保留一个换行；换行符 **LF**。
- 禁用通配符 import（含静态）；import 分组顺序 `io.github.mchgood` -> `org` -> `com` -> `java` -> `javax`（`com` 目前未使用，为将来依赖预留），静态 import 置底并按字典序。
- 折行方向：二元运算符换到**下一行开头**；点号与逗号留在**上一行末尾**。续行相对**语句起始列**缩进 8 空格（方法体一层 4 + 续行一层 4，与 Task 2-4 已落地并过审的形态一致；checkstyle `lineWrappingIndentation=4` 且 `forceStrictCondition=false` 接受该形态）。
- 注解独占一行（`AnnotationLocation` 的三个 `allowSameline*` 属性全部置 `false`；10.21.4 没有 `allowSamelineSingleAnnotations` 这个属性，写错会让 `TreeWalker` 初始化失败）。
- 代码中不得出现内联全限定类名，一律改为 import（已知 4 处，见 Task 4/6/8）。
- 整改过程**只**新增 `docs/superpowers/**`，不改动 `docs/requirements.md`、`docs/technical-design.md`、`docs/quick-start.md`、`docs/spring-boot.md`、`docs/testing-coverage.md`、`README.md`。
- 未经本计划明确列出的 git 操作一律不执行；不使用 `git commit --amend`、不 force push、不改 git config、不跳过 hook。
- 每个 commit 前工作区只包含该任务预期的改动，`git status --short` 必须逐条核对。

---

### 0. token 等价校验的分级协议（Task 2 复核后确立，对所有后续任务生效）

Task 2 实测暴露了两个校验设计缺陷，协议如下：

1. **严格 token 等价（主闸门）**：`python3 /tmp/style-equiv.py <上一个 commit> <改动文件>`。它证明改动只涉及空白、大括号、import、注释与改名词表内的标识符。
2. **允许的差异类别只有两类**：
   - (a) **R3 一行多变量声明拆分**。`T a, b;` -> `T a; T b;` 是 token 结构变化（一个 `;` 变两个、类型 token 被重新写出），严格校验**必然** FAIL，这不是缺陷。处理方式：在报告里逐条列出每个拆分点（文件:行、改前 -> 改后全文），并把「改前形态」与「改后形态」两种 fold 模式补进 `/tmp/style-equiv-decl.py` 的 `FOLDS` 列表（两侧同时折叠），直到 `FOLD-EQUIV ... failed=0`。任务 reviewer 会把枚举与 diff 逐条比对。
   - (b) §6.3 白名单内的 8 处超长字符串字面量折行（已在 `EXEMPT`）。
   - (c) **字符串字面量内容被替换为 `STR`，因此消息文本的改动对本校验不可见**。「错误码、消息文本、数值逐字保留」由 reviewer 直接读 diff 逐字节核对，不能依赖本闸门。
3. **任何其他差异都是缺陷**：语句被删改、阈值/字面量变化、调用顺序变化，都必须修回原语义，**禁止**扩大 `EXEMPT` 或 `FOLDS` 来掩盖。
4. **改名为等价类映射而非擦除**（Task 2 复核后升级）：脚本把旧名基础形与新名映射到同一个类代表元（旧名基础形），而不是都擦成 `NAME`。这样改名两侧仍归一化一致，但**两个不同改写对之间交换实参会被检出**（已实测：把 `new Node(spec, incomingEdges, outgoingEdges)` 的两个实参互换，`/tmp/style-equiv-decl.py c51d999` 报 1 处 `FOLD-DIFF`）。仍需 reviewer 人工留意的是同一等价类内部的互换（如 `e` 同时映射到 `execution` 与 `exception`，二者互换无法靠名字区分），不过这类互换会因类型不同而编译失败，风险由编译器兜底。
5. **`git diff` 的 grep 过滤器不能作为「无改动」的证明**：凡排除「含新名行」的过滤器，必然把每条改名行的旧侧暴露出来。改用「完整读 diff + 逐 hunk 确认只属于已列类别」。
6. `/tmp/style-equiv.py` 的改名词表**按文件解析**（Task 3 修正：`nodeRecord` 同时是 `NodeContext.r` 与 `SpelConditionEvaluator.n` 的新名，全局表会让两个等价类互相覆盖而产生假分歧）。对不在词表里的文件回退到全局表，与旧行为一致。
7. 折叠校验脚本固定为 `/tmp/style-equiv-decl.py`（由 Task 2 的一次性脚本提升而来），**必须显式传入 base ref**，不得硬编码 `HEAD`（提交后会自我比较而失效）。
8. **折叠模式按文件划分**（Task 6 复核后修正）：`GLOBAL_FOLDS` 保持为空，所有模式放进 `FILE_FOLDS[<仓库相对路径>]`。全局模式会让「为 A 文件的 `@Override` 补齐而注册的折叠」同样命中 B 文件里同签名的匿名实现，从而**放过 B 文件里多余的 `@Override`**（Task 6 已实测三处失明并修复）。文件级隔离后，多余注解必然造成 token 差异而被检出。
9. **折叠闸门只能证明「多余的 `@Override` 会被检出」，不能证明「该有的都在」**：删除一个已批准的 `@Override` 不会产生 token 差异。因此 `@Override` 的**数量与位置**验收靠 `grep -c "@Override"`（基线 12 -> 目标 23）加 reviewer 逐处核对，不靠本闸门。

---

## 违规基线速查

整改前实测（Task 1 的扫描脚本会复现这些数字）：

| 指标 | main | test | 合计 |
| --- | --- | --- | --- |
| 控制流缺失 `{}` | 87 | 10 | 97 |
| 一行多语句 | 147 | 128 | 275 |
| 单行 > 120 字符 | 103 | 159 | 262 |
| 通配符 import | 19 处 / 9 文件 | 42 处 / 11 文件 | 61 处 / 20 文件 |
| 含 <=2 字符标识符的文件 | 9 | 8 | **17**（63 个不同标识符） |

`api`、`spi`、`exception`、`result`、`node/FlowNode.java`、全部 `package-info.java`、`boot/autoconfigure/*` 无短名，只需格式化。
> 首轮盘点（用形参/局部声明正则）漏掉了 **curried lambda 与多参 lambda** 里的短名（如 `()->c->1`、`forEach((id,n)->...)`、`(k,v)->v-1`），经 reviewer 复核补上 3 个：`OrderExample.java` 的 `n`、`SpringResolverContractTest.java` 的 `c`、`FlowCompiler.java:123` 的 `v`。故合计是 **17 个文件 / 63 个标识符**，而不是最初说的 15/60。后续扫描都以「所有紧邻 `->` 的标识符」为准，不再只看声明位置。

含短名的 15 个文件与各自的短名清单（实测，Task 1 Step 3 会把它固化成 JSON）：

```
core/main  config/EngineConfig.java            d
core/main  internal/compiler/FlowCompiler.java b l c d a e n s x q p v（`(k,v)->v-1`，`k` 在白名单内）
core/main  internal/compiler/MutableGraph.java in (out 同源)
core/main  internal/graph/Definition.java      in out
core/main  node/NodeContext.java               r
core/main  runtime/DefaultFlowEngine.java      t r d n e ex p a x f w
spring/main SpelConditionEvaluator.java        e c (另有常量 ALLOWED、局部 out)
spring/main SpringNodeResolver.java            e
examples/main OrderExample.java                n（`(id,n)->`，NodeRecord）
core/test  PackageBoundaryTest.java            p
core/test  CompilerContractTest.java           n c g ex
core/test  ValueContractTest.java              v d
core/test  FlowNodeTypeContractTest.java       d
core/test  RuntimeBoundaryTest.java            e c x r a b n t l ex
spring/test FlowEngineTest.java                n e c a b r x pf fs bd ex
spring/test SpringResolverContractTest.java    c（`()->c->1`，FlowNode lambda 参数）
examples/test OrderExampleTest.java            r
```

---

### Task 1: 建立校验脚手架

**Files:**
- Create: `/tmp/style-scan.py`（违规计数器，不入库）
- Create: `/tmp/style-equiv.py`（token 级语义等价校验，不入库）
- Create: `/tmp/rename-map.json`（术语表的可执行形式，不入库）
- Create: `/tmp/style-equiv-decl.py`（声明拆分折叠校验，Task 2 复核后加入；必须显式传 base ref）

**Interfaces:**
- Consumes: 无（首个任务）
- Produces:
  - `python3 /tmp/style-scan.py [dir]` -> 逐文件打印 `行数 超120字符行 一行多语句行 缺大括号行 通配符import数`，末行打印 `files=N lines=N long=N multi=N nobrace=N star=N`
  - `python3 /tmp/style-equiv.py <git-ref> <file>...` -> 全部等价时打印 `EQUIV checked=N failed=0` 并退出码 0；否则打印首个分歧位置与上下文并退出码 1
  - `/tmp/rename-map.json` -> `{"<仓库相对路径>": {"<旧名>": "<新名>", ...}, ...}`，被 `style-equiv.py` 读取以构建**擦除词表**（旧名基础形 + 全部新名）
  - `/tmp/baseline-scan.txt`、`/tmp/baseline-tests.txt`、`/tmp/baseline-coverage.txt`

- [ ] **Step 1: 写违规计数脚本 `/tmp/style-scan.py`**

```python
#!/usr/bin/env python3
"""统计阿里规约格式违规基线；只读，不修改任何文件。"""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '.')
FILES = sorted(p for p in ROOT.rglob('*.java') if 'target' not in p.parts)
NOBRACE = re.compile(r'\b(?:if|for|while)\s*\(.*\)\s*(?!;?\{)(?!\s*$)\S')
NOBRACE_ELSE = re.compile(r'\belse\s+(?!if\b)(?!\{)\S')
STAR_IMPORT = re.compile(r'^\s*import\s+(?:static\s+)?[\w.]+\.\*;')

totals = dict(files=0, lines=0, long=0, multi=0, nobrace=0, star=0)
for path in FILES:
    lines = path.read_text(encoding='utf-8').split('\n')
    long_lines = multi = nobrace = star = 0
    for raw in lines:
        stripped = raw.strip()
        if len(raw) > 120:
            long_lines += 1
        if STAR_IMPORT.match(raw):
            star += 1
        if stripped.startswith(('//', '*', '/*')):
            continue
        code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', stripped)
        if code.count(';') > 1:
            multi += 1
        if NOBRACE.search(code) and '{' not in code:
            nobrace += 1
        elif NOBRACE_ELSE.search(code):
            nobrace += 1
    totals['files'] += 1
    totals['lines'] += len(lines)
    totals['long'] += long_lines
    totals['multi'] += multi
    totals['nobrace'] += nobrace
    totals['star'] += star
    print('%5d %5d %5d %5d %3d  %s' % (len(lines), long_lines, multi, nobrace, star, path))

print('\nfiles=%(files)d lines=%(lines)d long=%(long)d multi=%(multi)d '
      'nobrace=%(nobrace)d star=%(star)d' % totals)
```

- [ ] **Step 2: 跑一次确认复现基线**

Run: `python3 /tmp/style-scan.py .`

Expected: 末行 `files=50 lines=3237 long=262 multi=275 nobrace=97 star=61`（`lines` 允许 +-5 浮动，其余四项必须完全一致）。若 `nobrace` 或 `star` 对不上，先修正正则再继续，不要跳过。
> **`nobrace` 只是趋势指标，不是验收闸门**（reviewer 实测确认）：补大括号后控制流头会被 120 列规则折行，此时正则既可能对已合规的折行 `if` 误报、也可能漏掉「`if` 头与其语句分行」的缺括号形态。因此后续任务**不得**用 `nobrace=0` 作为「已补齐大括号」的证明；权威闸门是 Task 10 checkstyle 的 `NeedBraces` + 205 个测试。`long`/`multi`/`star` 三项仍可作为硬性清零指标。

- [ ] **Step 3: 写术语表的可执行形式 `/tmp/rename-map.json`**

内容必须逐字包含下列映射。这是 Task 2-9 的权威清单，实施时不得临时增删：

```json
{
  "flow-engine-core/src/main/java/io/github/mchgood/flow/internal/graph/Definition.java": {
    "in": "incomingEdges", "out": "outgoingEdges"
  },
  "flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/MutableGraph.java": {
    "in": "incomingEdges", "out": "outgoingEdges"
  },
  "flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/FlowCompiler.java": {
    "b": "block", "l": "lineIndex", "c": "cursor", "d": "declaration", "e": "edge",
    "n": "node", "n_walk": "current", "v": "remaining",
    "s": "shared", "q": "queue", "p": "position",
    "loc": "location", "ex": "exception", "x": "boundaryEdge",
    "a_link": "link", "a_start": "start",
    "ctor_s": "source", "ctor_l": "line", "ctor_n": "number",
    "take_s": "text", "expect_s": "text", "error_c": "code", "error_s": "detail"
  },
  "flow-engine-core/src/main/java/io/github/mchgood/flow/runtime/DefaultFlowEngine.java": {
    "WORKER": "CURRENT_ENGINE", "pool": "workerPool", "permits": "admissionPermits",
    "t": "thread", "r": "root", "d": "definition", "n": "node", "p": "parent",
    "a": "ancestor", "x": "candidate", "f": "flowException", "w": "work",
    "e_exec": "execution", "e_catch": "exception",
    "ex_reject": "rejection", "ex_throwable": "failure",
    "max": "maxDepth", "yes": "matched", "lambda_r": "runnable"
  },
  "flow-engine-core/src/main/java/io/github/mchgood/flow/config/EngineConfig.java": {
    "d": "timeout"
  },
  "flow-engine-core/src/main/java/io/github/mchgood/flow/node/NodeContext.java": {
    "r": "nodeRecord"
  },
  "flow-engine-spring/src/main/java/io/github/mchgood/flow/spring/SpelConditionEvaluator.java": {
    "ALLOWED": "ALLOWED_NODE_KINDS", "c": "context", "n": "nodeRecord", "out": "view",
    "e_flow": "flowException", "e_runtime": "runtimeException"
  },
  "flow-engine-spring/src/main/java/io/github/mchgood/flow/spring/SpringNodeResolver.java": {
    "e_flow": "flowException", "e_beans": "beansException"
  },
  "flow-engine-core/src/test/java/io/github/mchgood/flow/architecture/PackageBoundaryTest.java": {
    "p": "path"
  },
  "flow-engine-core/src/test/java/io/github/mchgood/flow/compiler/CompilerContractTest.java": {
    "c": "compiler", "g": "definition", "ex": "exception", "n": "node", "loc": "location"
  },
  "flow-engine-core/src/test/java/io/github/mchgood/flow/contract/ValueContractTest.java": {
    "v": "value", "d": "duration"
  },
  "flow-engine-core/src/test/java/io/github/mchgood/flow/node/FlowNodeTypeContractTest.java": {
    "d": "diagnostic"
  },
  "flow-engine-core/src/test/java/io/github/mchgood/flow/runtime/RuntimeBoundaryTest.java": {
    "e": "flowEngine", "r": "result", "c_ctx": "context", "c_cond": "condition",
    "x": "exception", "a": "first", "b": "second", "n_node": "node",
    "n_counter": "counter", "t": "text", "l": "location", "ex": "exception"
  },
  "flow-engine-examples/src/test/java/io/github/mchgood/flow/OrderExampleTest.java": {
    "r": "result"
  },
  "flow-engine-examples/src/main/java/io/github/mchgood/flow/OrderExample.java": {
    "n": "nodeRecord"
  },
  "flow-engine-spring/src/test/java/io/github/mchgood/flow/spring/SpringResolverContractTest.java": {
    "c": "context"
  },
  "flow-engine-spring/src/test/java/io/github/mchgood/flow/FlowEngineTest.java": {
    "n": "node", "e": "flowEngine", "c": "context", "r": "result", "x": "exception",
    "a": "lowAmount", "b": "highAmount", "pf": "proxyFactory", "fs": "futures",
    "bd": "definition", "ex": "exception", "inv": "invocation"
  }
}
```

带 `_link`/`_start`/`_exec`/`_catch`/`_flow`/`_beans`/`_runtime`/`_ctx`/`_cond`/`_node`/`_counter`/`_reject`/`_throwable`/`_walk`/`ctor_`/`take_`/`expect_`/`error_`/`lambda_` 前后缀的键是**位置限定符**，用来区分同一字母在不同作用域的不同语义（例如 `DefaultFlowEngine` 里 `e` 既指 `Execution` 又指 `InterruptedException`，`FlowCompiler` 里 `n` 既指一般节点又指 `walk` 里的当前节点）。实施者按限定符人工定位到具体作用域；`style-equiv.py` 会把限定符剥掉，只取基础名。

> **为什么 `style-equiv.py` 不做「旧名 -> 新名」的正向替换**：同一个旧名在不同作用域会映射到不同新名（`e` -> `execution` 与 `exception`），扁平的整词正则无法区分作用域，正向替换必然出错。脚本改用**词表擦除**策略：把词表里所有标识符（旧名基础形 + 全部新名）在两侧同时替换为同一个占位符 `NAME`，这样比较的是「结构、字面量、数值、方法名、调用顺序」，与改名完全解耦。改错名由编译器、测试与 Task 10 的 checkstyle 命名规则兜底。

`GenericNodeIntegrationTest`、`SpelContractTest`、starter 与 examples 的其余 main 文件**无短名**，不出现在 JSON 里，只需格式化与展开 import。（`SpringResolverContractTest` 与 `OrderExample` 经复核有 1 个短名，已入表。）

- [ ] **Step 4: 写 token 级语义等价校验脚本 `/tmp/style-equiv.py`**

```python
#!/usr/bin/env python3
"""比对 git 旧版本与工作区版本，证明只改了空白/大括号/import/白名单改名。

策略：把改名词表里的标识符在两侧同时擦除为 NAME，然后删除空白与全部大括号、
剔除 package/import 行与注释、把字符串与字符字面量替换为 STR/CHR。
两侧的规范化结果必须逐字符相同，否则说明改到了语义。

用法: python3 /tmp/style-equiv.py <git-ref> <file> [<file>...]
退出码 0 = 全部等价; 1 = 存在未解释差异。
"""
import json
import pathlib
import re
import subprocess
import sys

MAP_PATH = pathlib.Path('/tmp/rename-map.json')
# 8 处超长 Mermaid 字面量需折行，字符串拼接会改变 token 流，逐个人工复核（规格 6.3）
EXEMPT = {
    'flow-engine-core/src/test/java/io/github/mchgood/flow/runtime/RuntimeBoundaryTest.java',
    'flow-engine-spring/src/test/java/io/github/mchgood/flow/FlowEngineTest.java',
}
QUALIFIER = re.compile(
    r'_(?:link|start|exec|catch|flow|beans|runtime|ctx|cond|node|counter|reject|throwable|walk)$'
    r'|^(?:ctor|take|expect|error|lambda)_')
# 与改名无关、但整改中会随排版出现/消失的噪音标识符，一并擦除
EXTRA_VOCAB = {'STR', 'CHR', 'NAME'}


def build_vocab():
    """词表 = 所有旧名基础形 + 所有新名，按长度降序，避免前缀互相干扰。"""
    data = json.loads(MAP_PATH.read_text(encoding='utf-8'))
    vocab = set(EXTRA_VOCAB)
    for mapping in data.values():
        for old, new in mapping.items():
            vocab.add(QUALIFIER.sub('', old))
            vocab.add(new)
    return sorted(vocab, key=len, reverse=True)


VOCAB = build_vocab()
IDENT = re.compile(r'\b[A-Za-z_$][A-Za-z0-9_$]*\b')


def normalize(source):
    """把源码规范化为与排版和改名无关的 token 流。"""
    kept = []
    in_block = False
    for line in source.split('\n'):
        stripped = line.strip()
        if in_block:
            if '*/' in stripped:
                in_block = False
            continue
        if stripped.startswith('/*'):
            if '*/' not in stripped:
                in_block = True
            continue
        if not stripped or stripped.startswith('//') or stripped.startswith('*'):
            continue
        if stripped.startswith('package ') or stripped.startswith('import '):
            continue
        kept.append(line)
    text = '\n'.join(kept)
    text = re.sub(r'"(?:[^"\\]|\\.)*"', 'STR', text)
    text = re.sub(r"'(?:[^'\\]|\\.)'", 'CHR', text)
    text = IDENT.sub(lambda m: 'NAME' if m.group(0) in set(VOCAB) else m.group(0), text)
    text = text.replace('{', '').replace('}', '')
    return re.sub(r'\s+', '', text)


def main():
    ref = sys.argv[1]
    failures = checked = 0
    vocab_set = set(VOCAB)
    for path in sys.argv[2:]:
        old = subprocess.run(['git', 'show', '%s:%s' % (ref, path)],
                             capture_output=True, text=True, check=True).stdout
        new = pathlib.Path(path).read_text(encoding='utf-8')
        if path in EXEMPT:
            print('EQUIV EXEMPT %s (long Mermaid literals; review manually)' % path)
            checked += 1
            continue
        left = normalize(old)
        right = normalize(new)
        checked += 1
        if left == right:
            continue
        failures += 1
        print('EQUIV FAIL %s' % path)
        for index in range(min(len(left), len(right))):
            if left[index] != right[index]:
                lo = max(0, index - 60)
                print('  first divergence at char %d' % index)
                print('  old: ...%s...' % left[lo:index + 60])
                print('  new: ...%s...' % right[lo:index + 60])
                break
        else:
            print('  length differs: old=%d new=%d' % (len(left), len(right)))
            print('  old tail: ...%s' % left[-80:])
            print('  new tail: ...%s' % right[-80:])
    print('\nvocab size=%d' % len(vocab_set))
    print('EQUIV checked=%d failed=%d' % (checked, failures))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
```

> 词表擦除会同时抹掉旧名与新名，因此脚本**不会**因为改名而报错，只会因为语句结构、运算符、数值、字符串、方法名或调用顺序的变化而报错。这正是我们要的：改名对错交给编译器和 checkstyle，语义等价交给本脚本。

- [ ] **Step 5: 用「合规改动」样本验证脚本不误报**

```bash
cd /Users/banma-3613/workspace/java/code/open/flow-engine
python3 - <<'PY'
import pathlib
p = pathlib.Path('flow-engine-core/src/main/java/io/github/mchgood/flow/config/EngineConfig.java')
t = p.read_text(encoding='utf-8')
t = t.replace(
    'for(var d:new Duration[]{nodeTimeout,gatewayTimeout,flowTimeout,closeTimeout})',
    'for (var timeout : new Duration[] {nodeTimeout, gatewayTimeout, flowTimeout, closeTimeout}) {')
t = t.replace(
    'if(d==null||d.isZero()||d.isNegative()||d.compareTo(Duration.ofDays(1))>0)'
    'throw new IllegalArgumentException("Invalid timeout");',
    '    if (timeout == null || timeout.isZero() || timeout.isNegative()\n'
    '            || timeout.compareTo(Duration.ofDays(1)) > 0) {\n'
    '        throw new IllegalArgumentException("Invalid timeout");\n'
    '    }\n'
    '}')
p.write_text(t)
PY
python3 /tmp/style-equiv.py HEAD flow-engine-core/src/main/java/io/github/mchgood/flow/config/EngineConfig.java
echo "EXIT=$?"
git checkout flow-engine-core/src/main/java/io/github/mchgood/flow/config/EngineConfig.java
```

Expected: 打印 `EQUIV checked=1 failed=0`、`EXIT=0`；`git checkout` 后 `git status --short` 无输出。

- [ ] **Step 6: 用「语义改动」样本验证脚本能抓到**

```bash
python3 - <<'PY'
import pathlib
p = pathlib.Path('flow-engine-core/src/main/java/io/github/mchgood/flow/config/EngineConfig.java')
t = p.read_text(encoding='utf-8')
assert 'maxSubflowDepth>32' in t
p.write_text(t.replace('maxSubflowDepth>32', 'maxSubflowDepth>64'))
PY
python3 /tmp/style-equiv.py HEAD flow-engine-core/src/main/java/io/github/mchgood/flow/config/EngineConfig.java
echo "EXIT=$?"
git checkout flow-engine-core/src/main/java/io/github/mchgood/flow/config/EngineConfig.java
git status --short
```

Expected: 打印 `EQUIV FAIL`、`failed=1`、`EXIT=1`；`git checkout` 后 `git status --short` **无输出**。

若这一步显示 `failed=0`，说明脚本失效，**必须修好脚本再往下走**——后续 8 个任务的语义安全全靠它。

- [ ] **Step 7: 记录基线快照**

```bash
python3 /tmp/style-scan.py . > /tmp/baseline-scan.txt
mvn -B verify 2>&1 | grep -E "Tests run:.*Failures: [0-9]+, Errors" | tail -6 > /tmp/baseline-tests.txt
python3 scripts/check-coverage.py > /tmp/baseline-coverage.txt
cat /tmp/baseline-tests.txt /tmp/baseline-coverage.txt
```

Expected: 四条 `Tests run: 98` / `84` / `22` / `1` 且 `Failures: 0, Errors: 0`；`LINE: 484/504 = 96.03%; minimum 95%`；`BRANCH: 575/639 = 89.98%; minimum 88%`；退出码 0。

本任务不产生 commit（三个脚本全在 `/tmp`）。

---

### Task 2: 跨文件字段改名 `in`/`out` -> `incomingEdges`/`outgoingEdges`

唯一横跨 main 与 test、横跨三个包的改名，必须原子完成，否则无法编译。**本任务只改名与拆行，不做任何其他格式化**——其余排版留给 Task 4/5/6，混在一起会让 review 失焦。

**Files:**
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/internal/graph/Definition.java`（字段声明 98、构造赋值 104-105、构造器局部 180-183、边装填 187-188）
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/MutableGraph.java:23`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/FlowCompiler.java`（全部 `.in` / `.out` 成员访问）
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/runtime/DefaultFlowEngine.java`（全部 `.in` / `.out` 成员访问）
- Modify: `flow-engine-core/src/test/java/io/github/mchgood/flow/architecture/PackageBoundaryTest.java:55,56,59,60`

**Interfaces:**
- Consumes: 无
- Produces（Task 4/5/6 依赖）：
  - `Definition.Node.incomingEdges` : `public final List<Edge>`
  - `Definition.Node.outgoingEdges` : `public final List<Edge>`
  - `MutableGraph.Node.incomingEdges` : `final List<Edge>`（包内可见）
  - `MutableGraph.Node.outgoingEdges` : `final List<Edge>`（包内可见）
  - 保持不变：`Definition.Edge.from` / `.to`、`Definition.EdgeSpec.from()` / `.to()`、`MutableGraph.Edge.from` / `.to`

- [ ] **Step 1: 确认工作区干净**

Run: `git status --short`

Expected: 无输出。

- [ ] **Step 2: 改 `Definition.Node` 的字段声明**

把：

```java
        /**
         * 不可修改的入边、出边邻接表。
         */
        public final List<Edge> in, out;
```

改为（满足 R3 一行一声明；Javadoc 原文按语义分配到两个字段，文字不改写、不新增内容）：

```java
        /**
         * 不可修改的入边邻接表。
         */
        public final List<Edge> incomingEdges;

        /**
         * 不可修改的出边邻接表。
         */
        public final List<Edge> outgoingEdges;
```

- [ ] **Step 3: 改 `Definition.Node` 的私有构造赋值**

```java
            in = Collections.unmodifiableList(incoming);
            out = Collections.unmodifiableList(outgoing);
```

改为：

```java
            incomingEdges = Collections.unmodifiableList(incoming);
            outgoingEdges = Collections.unmodifiableList(outgoing);
```

- [ ] **Step 4: 改 `Definition` 构造器里的局部多变量声明**

```java
        for (NodeSpec spec : orderedSpecs) {
            List<Edge> in = new ArrayList<>(), out = new ArrayList<>();
            incoming.put(spec.id(), in);
            outgoing.put(spec.id(), out);
            compiled.put(spec.id(), new Node(spec, in, out));
        }
```

改为：

```java
        for (NodeSpec spec : orderedSpecs) {
            List<Edge> incomingEdges = new ArrayList<>();
            List<Edge> outgoingEdges = new ArrayList<>();
            incoming.put(spec.id(), incomingEdges);
            outgoing.put(spec.id(), outgoingEdges);
            compiled.put(spec.id(), new Node(spec, incomingEdges, outgoingEdges));
        }
```

- [ ] **Step 5: 改 `MutableGraph.Node` 的字段声明**

```java
        final List<Edge> in=new ArrayList<>(),out=new ArrayList<>();
```

改为：

```java
        final List<Edge> incomingEdges = new ArrayList<>();
        final List<Edge> outgoingEdges = new ArrayList<>();
```

- [ ] **Step 6: 批量替换三个消费方的成员访问**

用整词边界正则，避免误伤 `incoming`、`outgoing`、`inFlight`、`System.out` 等：

```bash
python3 - <<'PY'
import pathlib, re
targets = [
    'flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/FlowCompiler.java',
    'flow-engine-core/src/main/java/io/github/mchgood/flow/runtime/DefaultFlowEngine.java',
    'flow-engine-core/src/test/java/io/github/mchgood/flow/architecture/PackageBoundaryTest.java',
]
for name in targets:
    path = pathlib.Path(name)
    text = path.read_text(encoding='utf-8')
    new = re.sub(r'\.in(?![\w])', '.incomingEdges', text)
    new = re.sub(r'\.out(?![\w])', '.outgoingEdges', new)
    path.write_text(new)
    print('%s changed=%s' % (name, text != new))
PY
```

Expected: 三行全部 `changed=True`。

- [ ] **Step 7: 确认没有残留引用点**

Run: `grep -rn "\.in\b\|\.out\b" --include="*.java" flow-engine-core flow-engine-spring flow-engine-examples flow-engine-spring-boot-starter | grep -v "/target/" | grep -vE "\.incoming|\.outgoing|System\.(in|out)"`

Expected: **无输出**。若有输出，逐个改成 `incomingEdges` / `outgoingEdges`，不要放宽改名。

- [ ] **Step 8: 编译并跑 core 全量测试**

Run: `mvn -q -pl flow-engine-core test 2>&1 | grep -E "Tests run:|ERROR|BUILD" | tail -10`

Expected: `Tests run: 98, Failures: 0, Errors: 0, Skipped: 0`，无 `ERROR`。

- [ ] **Step 9: 跑 spring 模块确认无跨模块漏改**

Run: `mvn -q -pl flow-engine-spring -am test 2>&1 | grep -E "Tests run:|ERROR|BUILD" | tail -6`

Expected: core `Tests run: 98` 与 spring `Tests run: 84`，均 `Failures: 0, Errors: 0`，无 `ERROR`。

- [ ] **Step 10: token 等价校验**

```bash
python3 /tmp/style-equiv.py HEAD \
  flow-engine-core/src/main/java/io/github/mchgood/flow/internal/graph/Definition.java \
  flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/MutableGraph.java \
  flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/FlowCompiler.java \
  flow-engine-core/src/main/java/io/github/mchgood/flow/runtime/DefaultFlowEngine.java \
  flow-engine-core/src/test/java/io/github/mchgood/flow/architecture/PackageBoundaryTest.java
```

Expected: 严格校验对 2 处声明拆分 FAIL 属预期（见第 0 节协议）；其余不得有差异。已实测：`failed=2`，分歧点恰为 `Definition.Node` 字段声明与 `MutableGraph.Node` 字段声明；`/tmp/style-equiv-decl.py` 用 6 条 fold 模式对两侧折叠后 `FOLD-EQUIV checked=5 failed=0`。

- [ ] **Step 11: 核对 diff 只含改名与拆行**

Run: `git diff --stat`

Expected: 恰好 5 个文件被改动。

**（原 Step 11 的 grep 过滤器预期「无输出」在逻辑上不可达成，已由第 0 节第 5 条取代。** 实际执行方式：完整读 `git diff`，逐 hunk 确认每行要么是 `in`/`out` 改名的镜像、要么是已列入 `FOLDS` 的声明拆分、要么是随拆分产生的 Javadoc `/**`/`*/` 分隔行。Task 2 已按此完成，reviewer 复核通过。）

- [ ] **Step 12: 提交**

```bash
git add flow-engine-core/src/main/java/io/github/mchgood/flow/internal/graph/Definition.java \
        flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/MutableGraph.java \
        flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/FlowCompiler.java \
        flow-engine-core/src/main/java/io/github/mchgood/flow/runtime/DefaultFlowEngine.java \
        flow-engine-core/src/test/java/io/github/mchgood/flow/architecture/PackageBoundaryTest.java
git status --short
git commit -m "refactor: rename graph adjacency fields to incomingEdges and outgoingEdges"
```

Expected: `git add` 后 `git status --short` 只显示 5 个 `M ` 条目，无其他文件。

---

### Task 3: 整改 core 契约包 main 源码

**Files:**
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/api/ExecutionOptions.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/api/FlowDescriptor.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/api/FlowEngine.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/api/package-info.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/spi/CompiledCondition.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/spi/ConditionEvaluator.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/spi/NodeResolver.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/spi/SourceLocation.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/spi/package-info.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/exception/FlowException.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/exception/package-info.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/result/ChildFlowResultView.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/result/FlowError.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/result/FlowResult.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/result/FlowStatus.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/result/NodeOutput.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/result/NodeRecord.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/result/NodeStatus.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/result/package-info.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/config/EngineConfig.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/config/package-info.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/node/FlowNode.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/node/NodeContext.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/node/package-info.java`

**Interfaces:**
- Consumes: 无（Task 2 已改完 `internal.graph`，本任务不碰 internal 包）
- Produces: 契约包对外签名**逐字不变**——`EngineConfig` 的 11 个 record 组件顺序与类型、`EngineConfig.defaults()`、`ExecutionOptions.timeout()` / `withTimeout(Duration)`、`NodeContext.ancestors()` / `input()` / `ancestorOutput(String)` / `ancestorValue(String, Class<T>)`、`FlowResult` 全部访问器、`SourceLocation.source()` / `line()` / `column()`、`FlowException.code()`、`FlowNode<O>.execute(NodeContext)`。Task 4/5/7 依赖这些签名。

- [ ] **Step 1: 确认工作区干净**

Run: `git status --short && git log --oneline -1`

Expected: 无输出 + `refactor: rename graph adjacency fields to incomingEdges and outgoingEdges`。

- [ ] **Step 2: 改 `EngineConfig.java` 的紧凑构造器**

把：

```java
    public EngineConfig {
        if(workerThreads<1||queueCapacity<1||maxConcurrentExecutions<1||maxInFlightPerExecution<1||maxSubflowDepth<0||maxSubflowDepth>32||maxExecutionsPerRoot<1||maxActiveChildren<1)throw new IllegalArgumentException("Invalid capacities");
        for(var d:new Duration[]{nodeTimeout,gatewayTimeout,flowTimeout,closeTimeout})
            if(d==null||d.isZero()||d.isNegative()||d.compareTo(Duration.ofDays(1))>0)throw new IllegalArgumentException("Invalid timeout");
    }
```

改为（R1 补大括号、R2 一行一语句、R4 空格、R5 折行、R11 `d` -> `timeout`；**阈值 `1`/`0`/`32`/`ofDays(1)` 与两句异常文案逐字保留，条件项顺序不变**）：

```java
    public EngineConfig {
        if (workerThreads < 1 || queueCapacity < 1 || maxConcurrentExecutions < 1
                || maxInFlightPerExecution < 1 || maxSubflowDepth < 0 || maxSubflowDepth > 32
                || maxExecutionsPerRoot < 1 || maxActiveChildren < 1) {
            throw new IllegalArgumentException("Invalid capacities");
        }
        for (var timeout : new Duration[] {nodeTimeout, gatewayTimeout, flowTimeout, closeTimeout}) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()
                    || timeout.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("Invalid timeout");
            }
        }
    }
```

- [ ] **Step 3: 改 `EngineConfig.java` 的 record 头与静态工厂**

```java
public record EngineConfig(int workerThreads,int queueCapacity,int maxConcurrentExecutions,
    int maxInFlightPerExecution,int maxSubflowDepth,int maxExecutionsPerRoot,int maxActiveChildren,
    Duration nodeTimeout,Duration gatewayTimeout,Duration flowTimeout,Duration closeTimeout) {
```

改为（组件顺序与类型不变，逗号留上一行末尾）：

```java
public record EngineConfig(int workerThreads, int queueCapacity, int maxConcurrentExecutions,
        int maxInFlightPerExecution, int maxSubflowDepth, int maxExecutionsPerRoot, int maxActiveChildren,
        Duration nodeTimeout, Duration gatewayTimeout, Duration flowTimeout, Duration closeTimeout) {
```

```java
    public static EngineConfig defaults(){return new EngineConfig(8,128,64,8,8,128,32,Duration.ofSeconds(30),Duration.ofSeconds(1),Duration.ofSeconds(60),Duration.ofSeconds(10));}
```

改为（默认值 `8,128,64,8,8,128,32` 与 `30s,1s,60s,10s` **逐字不变**）：

```java
    public static EngineConfig defaults() {
        return new EngineConfig(8, 128, 64, 8, 8, 128, 32,
                Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(60), Duration.ofSeconds(10));
    }
```

- [ ] **Step 4: 改 `ExecutionOptions.java` 的紧凑构造器**

```java
    public ExecutionOptions { if(timeout!=null&&(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofDays(1))>0))throw new IllegalArgumentException("timeout must be in (0, 1 day]"); }
```

改为（条件结构与文案逐字不变）：

```java
    public ExecutionOptions {
        if (timeout != null
                && (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofDays(1)) > 0)) {
            throw new IllegalArgumentException("timeout must be in (0, 1 day]");
        }
    }
```

- [ ] **Step 5: 改 `NodeContext.java`**

```java
    public NodeOutput ancestorOutput(String id){var r=record(id);return new NodeOutput(r.present(),r.value());}
```

改为：

```java
    public NodeOutput ancestorOutput(String id) {
        var nodeRecord = record(id);
        return new NodeOutput(nodeRecord.present(), nodeRecord.value());
    }
```

```java
    private NodeRecord record(String id){
        var r=ancestors.get(id);
        if(r==null)throw new FlowException("CONTEXT_ACCESS_DENIED","Not an ancestor: "+id);
        return r;
    }
```

改为（方法名 `record` 保留，错误码与文案逐字不变）：

```java
    private NodeRecord record(String id) {
        var nodeRecord = ancestors.get(id);
        if (nodeRecord == null) {
            throw new FlowException("CONTEXT_ACCESS_DENIED", "Not an ancestor: " + id);
        }
        return nodeRecord;
    }
```

同文件 `ancestorValue` 与 `ancestorStatus` 里的单语句 `if` 补大括号（`out` 是 3 字符，名字保留）：

```java
        if (!out.present()) {
            throw new FlowException("MISSING_NODE_OUTPUT", id);
        }
```

```java
        if (nodeRecord == null) {
            throw new FlowException("CONTEXT_ACCESS_DENIED", "Not an ancestor: " + id);
        }
```

- [ ] **Step 6: 改剩余 19 个文件**

对 `api/FlowDescriptor.java`、`api/FlowEngine.java`、`api/package-info.java`、`spi/` 5 个、`exception/` 2 个、`result/` 其余 6 个、`config/package-info.java`、`node/FlowNode.java`、`node/package-info.java` 逐个应用 R1-R10：

- 关键字后补空格：`if(` -> `if (`、`for(` -> `for (`、`while(` -> `while (`、`catch(` -> `catch (`、`switch(` -> `switch (`、`synchronized(` -> `synchronized (`、`try{` -> `try {`、`}finally{` -> `} finally {`、`}catch(` -> `} catch (`
- 运算符两侧补空格：`==` `!=` `<` `>` `<=` `>=` `&&` `||` `+` `-` `*` `/` `%` `=` `+=` `-=`（字符串字面量内部不动）
- 逗号后补空格
- 单语句控制流补 `{}`，`{` 留行尾，`}` 独占一行
- 一行多语句拆成多行
- 一行多变量声明拆成多行，每个声明复制原有 Javadoc 文字（不改写、不新增内容）
- 超 120 字符按折行方向规则换行
- 注解移到独占一行（`@Override public X y(){` -> `@Override` 换行 + `public X y() {`）；本任务范围内**不新增** `@Override`（11 处缺失覆写分属 Task 6/7 的文件）
- record 空体与接口空体统一写作 `{}`；**单语句方法体允许保持一行**（如 `public String code() { return code; }`），`RightCurly` 对 `METHOD_DEF` 取 `alone_or_singleline` 放行；此为 Task 3 已过审的先例，Task 5 及之后保持一致
- 展开通配符 import

- [ ] **Step 7: 展开本任务的 3 处通配符 import**

涉及 `api/FlowEngine.java`、`node/NodeContext.java`、`result/FlowResult.java` 各自的 `import java.util.*;`。

方法：删掉通配符 import，编译，按 `cannot find symbol` 逐个补具体 import，循环直到干净：

```bash
mvn -q -pl flow-engine-core -am compile 2>&1 | grep -E "symbol:|location:" | head -30
```

Expected: 反复「编译 -> 补 import」直到该命令**无输出**。

- [ ] **Step 8: 编译并跑 core 测试**

Run: `mvn -q -pl flow-engine-core test 2>&1 | grep -E "Tests run:|ERROR|BUILD" | tail -10`

Expected: `Tests run: 98, Failures: 0, Errors: 0, Skipped: 0`，无 `ERROR`。

- [ ] **Step 9: token 等价校验**

```bash
python3 /tmp/style-equiv.py HEAD $(git diff --name-only HEAD -- 'flow-engine-core/src/main/java/io/github/mchgood/flow/api' \
  'flow-engine-core/src/main/java/io/github/mchgood/flow/spi' \
  'flow-engine-core/src/main/java/io/github/mchgood/flow/exception' \
  'flow-engine-core/src/main/java/io/github/mchgood/flow/result' \
  'flow-engine-core/src/main/java/io/github/mchgood/flow/config' \
  'flow-engine-core/src/main/java/io/github/mchgood/flow/node')
```

Expected: 除 R3 声明拆分点外 `failed=0`（拆分点按第 0 节协议第 2(a) 条处理并补入 `FOLDS`）。

若某文件 FAIL，读脚本打印的 `first divergence` 上下文定位并**修回原语义**，不得把文件加进 `EXEMPT`。`NodeContext.java` 的 `r` -> `nodeRecord` 已在 `rename-map.json` 里；若报 FAIL 且分歧点是 Javadoc 文字，说明改动了注释内容，改回来。

- [ ] **Step 10: 局部扫描清零确认**

```bash
python3 /tmp/style-scan.py flow-engine-core/src/main/java/io/github/mchgood/flow/api
python3 /tmp/style-scan.py flow-engine-core/src/main/java/io/github/mchgood/flow/spi
python3 /tmp/style-scan.py flow-engine-core/src/main/java/io/github/mchgood/flow/exception
python3 /tmp/style-scan.py flow-engine-core/src/main/java/io/github/mchgood/flow/result
python3 /tmp/style-scan.py flow-engine-core/src/main/java/io/github/mchgood/flow/config
python3 /tmp/style-scan.py flow-engine-core/src/main/java/io/github/mchgood/flow/node
```

Expected: 六条命令的末行**全部**是 `long=0 multi=0 star=0`，且 `nobrace` 相比整改前明显下降。**不要**把 `nobrace=0` 当作通过条件（见 Task 1 Step 2 的说明）。

- [ ] **Step 11: 提交**

```bash
git add flow-engine-core/src/main/java/io/github/mchgood/flow/api \
        flow-engine-core/src/main/java/io/github/mchgood/flow/spi \
        flow-engine-core/src/main/java/io/github/mchgood/flow/exception \
        flow-engine-core/src/main/java/io/github/mchgood/flow/result \
        flow-engine-core/src/main/java/io/github/mchgood/flow/config \
        flow-engine-core/src/main/java/io/github/mchgood/flow/node
git status --short
git commit -m "style: reformat flow-engine-core contract packages per Alibaba conventions"
```

Expected: `git add` 后 `git status --short` 只显示 24 个 `M ` 条目（无新增/删除文件），全部位于上述 6 个包内。

---

### Task 4: 整改 core `internal.graph` 与 `internal.compiler` main 源码

**Files:**
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/internal/graph/Definition.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/internal/graph/package-info.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/FlowCompiler.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/MutableGraph.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/package-info.java`

**Interfaces:**
- Consumes: Task 2 的 `incomingEdges` / `outgoingEdges`；Task 3 的契约包签名（未变）。
- Produces（Task 5 依赖）：
  - `FlowCompiler(NodeResolver resolver, ConditionEvaluator evaluator)` 与 `compile(String id, String markdown)` : `Definition`，签名逐字不变
  - `FlowCompiler.error(String code, SourceLocation location, String text)` : `static FlowException`（形参 `loc` -> `location`，方法名与返回类型不变）
  - `FlowCompiler.Decl(String id, String label, String shape, SourceLocation location)`（record 组件 `loc` -> `location`，访问器随之变为 `location()`；`Decl` 是私有 record，无外部引用）
  - `MutableGraph.Node(String id, String label, String target, Type type, SourceLocation location)`、`MutableGraph.Edge(Node from, Node to, String text, SourceLocation location)`、`MutableGraph.Edge.fallback()`，签名逐字不变
  - `Definition.Type` 的 8 个枚举常量、`Definition.NodeSpec`、`Definition.EdgeSpec`、`Definition.id` / `.hash` / `.nodes` / `.ordered` / `.descriptor()`，全部逐字不变

- [ ] **Step 1: 确认工作区干净**

Run: `git status --short`

Expected: 无输出。

- [ ] **Step 2: 重写 `MutableGraph.java`**

整个类只有 37 行，按 R1-R10 重写为目标形态（字段名、类型、可见性、Javadoc 文字全部保持，只改排版；`java.util.*` 展开为 4 个具体类型）：

```java
package io.github.mchgood.flow.internal.compiler;

import io.github.mchgood.flow.internal.graph.Definition.Type;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.spi.CompiledCondition;
import io.github.mchgood.flow.spi.SourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 仅供单次编译使用的可变图草稿容器，不对运行时暴露。
 * <p>边连接、网关类型修正、祖先集合和 Bean 绑定在编译期逐步填充，完成后复制为 Definition。
 */
final class MutableGraph {

    /**
     * 编译期节点草稿；入出边和祖先集合允许原地追加，仅由本次编译线程访问。
     */
    static final class Node {

        final String id;
        final String label;
        final String target;
        final SourceLocation location;
        final List<Edge> incomingEdges = new ArrayList<>();
        final List<Edge> outgoingEdges = new ArrayList<>();
        final Set<String> ancestors = new LinkedHashSet<>();
        Type type;
        FlowNode<?> bean;

        Node(String id, String label, String target, Type type, SourceLocation location) {
            this.id = id;
            this.label = label;
            this.target = target;
            this.type = type;
            this.location = location;
        }
    }

    /**
     * 编译期边草稿；条件文本保留源码位置，condition 在校验时预解析。
     */
    static final class Edge {

        final String id;
        final Node from;
        final Node to;
        final String text;
        final SourceLocation location;
        CompiledCondition condition;

        Edge(Node from, Node to, String text, SourceLocation location) {
            this.from = from;
            this.to = to;
            this.text = text;
            this.location = location;
            id = from.id + "->" + to.id;
        }

        boolean fallback() {
            return "default".equals(text);
        }
    }
}
```

- [ ] **Step 3: 改 `Definition.java` 的多变量字段声明**

把 `Node` 的 `public final String id, label, target;` 拆成三个独立声明，每个上方复制原有 Javadoc 文字（原文不改写）：

```java
        /**
         * 完整图 ID、显示标签与去别名后的调用目标；控制节点 target 为 null。
         */
        public final String id;

        /**
         * 完整图 ID、显示标签与去别名后的调用目标；控制节点 target 为 null。
         */
        public final String label;

        /**
         * 完整图 ID、显示标签与去别名后的调用目标；控制节点 target 为 null。
         */
        public final String target;
```

把 `Edge` 的 `public final String id, text;` 与 `public final Node from, to;` 同样各拆成独立声明，各自复制原 Javadoc 文字。把 `Definition` 自身的 `public final String id, hash;` 拆成两个独立声明，各自复制原 Javadoc 文字。

- [ ] **Step 4: 改 `Definition.java` 的构造器与方法体**

```java
            id = spec.id(); label = spec.label(); target = spec.target();
            type = spec.type(); location = spec.location(); bean = spec.bean();
```

改为：

```java
            id = spec.id();
            label = spec.label();
            target = spec.target();
            type = spec.type();
            location = spec.location();
            bean = spec.bean();
```

```java
            text = spec.text(); location = spec.location(); condition = spec.condition();
```

改为：

```java
            text = spec.text();
            location = spec.location();
            condition = spec.condition();
```

单行方法体展开为多行：

```java
        public boolean fallback() {
            return "default".equals(text);
        }
```

```java
    public FlowDescriptor descriptor() {
        return new FlowDescriptor(id, hash, nodes.size());
    }
```

`import java.util.*` 展开为实际使用的具体类型（`ArrayList`、`Collections`、`HashMap`、`LinkedHashMap`、`LinkedHashSet`、`List`、`Map`、`Objects`、`Set`，以编译结果为准多退少补）。

- [ ] **Step 5: 替换 `FlowCompiler.java` 的 import 块**

把原第 3-18 行整体替换为（顺序按 Global Constraints，静态 import 置底且展开）：

```java
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.internal.graph.Definition;
import io.github.mchgood.flow.internal.graph.Definition.Type;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.NodeResolver;
import io.github.mchgood.flow.spi.SourceLocation;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static io.github.mchgood.flow.internal.compiler.MutableGraph.Edge;
import static io.github.mchgood.flow.internal.compiler.MutableGraph.Node;
```

> 以「编译无 `cannot find symbol`」为准多退少补；Task 10 的 `UnusedImports` 会兜住多余的。

- [ ] **Step 6: 改 `FlowCompiler.java` 的内联全限定名与 catch**

```java
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
```

改为（import 已在 Step 5 加入）：

```java
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
```

- [ ] **Step 7: 改 `Decl` record 组件与 `error` 静态方法形参**

```java
    private record Decl(String id,String label,String shape,SourceLocation loc){}
```

改为（原 Javadoc 文字不变，位置符合缩进要求）：

```java
    /**
     * 单行解析得到的节点声明；shape 为 null 表示仅引用已有节点，尚无显式形状。
     */
    private record Decl(String id, String label, String shape, SourceLocation location) {
    }
```

```java
    static FlowException error(String code,SourceLocation loc,String text){return new FlowException(code,loc+" "+text);}
```

改为：

```java
    static FlowException error(String code, SourceLocation location, String text) {
        return new FlowException(code, location + " " + text);
    }
```

全文 `d.loc` -> `declaration.location()`；`Decl node()` 里的 `SourceLocation loc=new SourceLocation(source,number,a+1);` -> `SourceLocation location = new SourceLocation(source, number, start + 1);`，`return new Decl(id,label,shape,loc);` -> `return new Decl(id, label, shape, location);`。同步把 `error` 方法 Javadoc 里的 `@param loc` 改为 `@param location`（只改参数名，描述文字不变）。

- [ ] **Step 8: 改 `FlowCompiler.java` 的局部变量名**

逐处应用（**只改名，不改任何条件、阈值、错误码、消息文本**）：

| 位置 | 旧 | 新 |
| --- | --- | --- |
| mermaid 块收集 lambda | `visit(FencedCodeBlock b)` | `visit(FencedCodeBlock block)`；`b.getInfo()` -> `block.getInfo()` |
| 行循环 | `for(int l=0;l<lines.length;l++)` | `for (int lineIndex = 0; lineIndex < lines.length; lineIndex++)` |
| 行循环体 | `var c=new Cursor(id,lines[l],base+l)` | `var cursor = new Cursor(id, lines[lineIndex], base + lineIndex)` |
| 游标全部调用 | `c.space()` `c.end()` `c.rest()` `c.take()` `c.expect()` `c.until()` `c.error()` `c.node()` | `cursor.` 前缀 |
| 声明合并循环 | `for(var d:declarations.values())` | `for (var declaration : declarations.values())` |
| 边构建 | `var a=links.get(i)` | `var link = links.get(i)`；`a[0]`/`a[1]`/`a[2]` -> `link[0]`/`link[1]`/`link[2]` |
| 边构建 | `var e=new Edge(from,to,a[2],locations.get(i))` | `var edge = new Edge(from, to, link[2], locations.get(i))`；`e.id`/`e.location` -> `edge.id`/`edge.location` |
| 后支配集合 | `Set<Node> s=new HashSet<>()` | `Set<Node> shared = new HashSet<>()`；`s.addAll`/`s.retainAll`/`s.add`/`post.put(n,s)` -> `shared` |
| 入边校验 lambda | `n.in.stream().anyMatch(x->x.from!=split&&!region.contains(x.from))` | `node.incomingEdges.stream().anyMatch(incoming -> incoming.from != split && !region.contains(incoming.from))` |
| 出边校验 lambda | `n.out.stream().anyMatch(x->x.to!=join&&!region.contains(x.to))` | `node.outgoingEdges.stream().anyMatch(outgoing -> outgoing.to != join && !region.contains(outgoing.to))` |
| 出口计数 lambda | `region.stream().flatMap(n->n.out.stream()).filter(x->x.to==join)` | `region.stream().flatMap(node -> node.outgoingEdges.stream()).filter(outgoing -> outgoing.to == join)` |
| 汇合入边 lambda | `join.in.stream().anyMatch(e->e.from!=split&&!union.contains(e.from))` | `join.incomingEdges.stream().anyMatch(edge -> edge.from != split && !union.contains(edge.from))` |
| 配对查找 lambda | `order.stream().filter(n->n!=split&&post.get(split).contains(n))` | `order.stream().filter(node -> node != split && post.get(split).contains(node))` |
| `walk` | `Deque<Node> q=new ArrayDeque<>();q.add(node);` | `Deque<Node> queue = new ArrayDeque<>();` + `queue.add(node);` 拆两行；形参 `node` **保留不改**（已合规），`q.isEmpty()`/`q.remove()` -> `queue.` |
| `walk` | `var n=q.remove()` / `for(var e:reverse?n.in:n.out)` / `q.add(reverse?e.from:e.to)` | `var current = queue.remove()` / `for (var edge : reverse ? current.incomingEdges : current.outgoingEdges)` / `queue.add(reverse ? edge.from : edge.to)`。局部用 `current` 而非 `node`，因为形参已占用 `node` |
| 拓扑入度 lambda | `degrees.compute(e.to,(k,v)->v-1)` | `degrees.compute(edge.to, (node, remaining) -> remaining - 1)`；`k` 在白名单内**保留**，`v` -> `remaining` |
| `merge` 形参 | `merge(Map<String,Decl> map,Decl d)` | `merge(Map<String, Decl> map, Decl declaration)`；`map` 是 3 字符且语义清楚，**保留**；体内 `d.` -> `declaration.` |
| 拓扑/祖先循环 | `for(var n:order)` `for(var e:n.out)` `for(var n:nodes.values())` | `for (var node : order)` `for (var edge : node.outgoingEdges)` `for (var node : nodes.values())` |
| 就绪队列 | `nodes.values().forEach(n->{degrees.put(n,n.in.size());if(n.in.isEmpty())ready.add(n);});` | `node` 形参 + 补 `{}` + 拆行 |
| `Cursor` 字段 | `int p` | `int position`；全部 `p` 引用改 `position` |
| `Cursor` 构造器 | `Cursor(String s,String l,int n){source=s;line=l;number=n;}` | `Cursor(String source, String line, int number)` + 三行赋值 |
| `Cursor.take` / `Cursor.expect` 形参 | `String s` | `String text` |
| `Cursor.error` 形参 | `error(String c,String s)` | `error(String code, String detail)`；体内 `FlowCompiler.error(c,new SourceLocation(source,number,p+1),s)` -> `FlowCompiler.error(code, new SourceLocation(source, number, position + 1), detail)` |
| `Cursor.until` 局部 | `int a=p` | `int start = position` |
| `Cursor.node` 局部 | `int a=p` | `int start = position` |
| 发布图 lambda | `order.stream().map(n -> new Definition.NodeSpec(n.id,...))` | `node -> new Definition.NodeSpec(node.id, node.label, node.target, node.type, node.location, node.bean, node.ancestors)` |
| 发布图 lambda | `nodes.values().stream().flatMap(n -> n.out.stream()).map(e -> new Definition.EdgeSpec(e.from.id,e.to.id,e.text,e.location,e.condition))` | `node -> node.outgoingEdges.stream()` 与 `edge -> new Definition.EdgeSpec(edge.from.id, edge.to.id, edge.text, edge.location, edge.condition)` |

- [ ] **Step 9: 全量重排 `FlowCompiler.java` 的排版**

对该文件逐行应用 R1-R5、R8-R10。重点：

- 每条 `if(...)throw ...;` 补 `{}` 并展开为三行
- `while(!ready.isEmpty()){var n=ready.remove();order.add(n);for(var e:n.out)if(...)ready.add(e.to);}` 这类嵌套单行全部展开，每层缩进 4 空格
- 原第 130 行那条 300+ 字符的 `return new Definition(...)` 按折行方向规则拆成多行，`NodeSpec` / `EdgeSpec` 的实参顺序与数量不变
- `%%` 注释行、`flowchart TD|LR` 头校验、`links.size()>4096`、`declarations.size()>512`、`n.out.size()>32`、`defaults>1`、`1_048_576`、正则 `"[a-z][A-Za-z0-9]*"` 与 `"[a-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+(?:_[A-Za-z0-9]+)*)?"`、`"[A-Za-z_][A-Za-z0-9_]*"`、`"%%\\s*@bean.*"`、`"%%{"`、`"flowchart\\s+(TD|LR)\\s*"` 等**所有阈值、正则、错误码、消息文本逐字保留**
- 嵌套类 `Cursor` 与方法 `space()` / `end()` / `rest()` / `take()` / `expect()` / `until()` / `error()` / `node()` 的**方法名与返回类型不变**

- [ ] **Step 10: 编译并跑 core 测试**

Run: `mvn -q -pl flow-engine-core test 2>&1 | grep -E "Tests run:|ERROR|BUILD" | tail -10`

Expected: `Tests run: 98, Failures: 0, Errors: 0, Skipped: 0`，无 `ERROR`。

- [ ] **Step 11: token 等价校验**

```bash
python3 /tmp/style-equiv.py HEAD \
  flow-engine-core/src/main/java/io/github/mchgood/flow/internal/graph/Definition.java \
  flow-engine-core/src/main/java/io/github/mchgood/flow/internal/graph/package-info.java \
  flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/FlowCompiler.java \
  flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/MutableGraph.java \
  flow-engine-core/src/main/java/io/github/mchgood/flow/internal/compiler/package-info.java
```

Expected: 除 R3 声明拆分点外 `failed=0`（拆分点按第 0 节协议第 2(a) 条处理并补入 `FOLDS`）。

若 `FlowCompiler.java` FAIL，按 `first divergence` 上下文定位：改名类差异已被词表擦除，不会触发 FAIL，所以分歧点必然落在**语句结构、运算符、数值、字符串或方法名**上——那是真实的语义改动，必须修回原样。**不得**把文件加进 `EXEMPT`。若确认某个新引入的标识符（例如 `boundaryEdge`）不在词表里，把它作为某条映射的值补进 `/tmp/rename-map.json` 对应文件的条目后重跑。

- [ ] **Step 12: 局部扫描清零确认**

Run: `python3 /tmp/style-scan.py flow-engine-core/src/main/java/io/github/mchgood/flow/internal | tail -3`

Expected: 末行 `long=0 multi=0 star=0`（`nobrace` 仅作趋势参考，不作通过条件）。

- [ ] **Step 13: 提交**

```bash
git add flow-engine-core/src/main/java/io/github/mchgood/flow/internal
git status --short
git commit -m "style: reformat flow-engine-core internal packages per Alibaba conventions"
```

Expected: `git add` 后 `git status --short` 只显示 5 个 `M ` 条目。

---

### Task 5: 整改 core `runtime` main 源码

**Files:**
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/runtime/DefaultFlowEngine.java`
- Modify: `flow-engine-core/src/main/java/io/github/mchgood/flow/runtime/package-info.java`

**Interfaces:**
- Consumes: Task 2 的 `incomingEdges` / `outgoingEdges`；Task 4 的 `Definition.Type` / `.Node` / `.Edge`（经静态 import）与 `FlowCompiler`。
- Produces（Task 6/7/8/9 依赖）：`DefaultFlowEngine` 的三个公开构造器与 `register(String, String)` / `registerAll(Map<String,String>)` / `execute(String, Object, ExecutionOptions)` / `close()` 签名**逐字不变**。`WORKER` -> `CURRENT_ENGINE`、`pool` -> `workerPool`、`permits` -> `admissionPermits` 均为 `private`，无外部引用。私有嵌套类 `Root` / `Execution` / `RuntimeNode` / `Work` 的**类型名与字段名不变**（除上列三个）。

- [ ] **Step 1: 确认工作区干净**

Run: `git status --short`

Expected: 无输出。

- [ ] **Step 2: 替换 import 块**

把原第 3-26 行整体替换为（以「编译无 `cannot find symbol`」为准多退少补；ASCII 字典序）：

```java
import io.github.mchgood.flow.api.ExecutionOptions;
import io.github.mchgood.flow.api.FlowDescriptor;
import io.github.mchgood.flow.api.FlowEngine;
import io.github.mchgood.flow.config.EngineConfig;
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.internal.compiler.FlowCompiler;
import io.github.mchgood.flow.internal.graph.Definition;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.result.ChildFlowResultView;
import io.github.mchgood.flow.result.FlowError;
import io.github.mchgood.flow.result.FlowResult;
import io.github.mchgood.flow.result.FlowStatus;
import io.github.mchgood.flow.result.NodeRecord;
import io.github.mchgood.flow.result.NodeStatus;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.NodeResolver;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static io.github.mchgood.flow.internal.graph.Definition.Edge;
import static io.github.mchgood.flow.internal.graph.Definition.Node;
import static io.github.mchgood.flow.internal.graph.Definition.Type;
```

- [ ] **Step 3: 改静态字段与实例字段**

```java
    private static final ThreadLocal<DefaultFlowEngine> WORKER=new ThreadLocal<>();
    private static final System.Logger LOG=System.getLogger(DefaultFlowEngine.class.getName());
    private final FlowCompiler compiler;private final ConditionEvaluator evaluator;private final EngineConfig config;
    private final ThreadPoolExecutor pool;private final Semaphore permits;
    private final Object registryLock=new Object();
    private volatile Map<String,Definition> registry=Map.of();private volatile boolean closed;
    private final Set<Root> roots=ConcurrentHashMap.newKeySet();
```

改为：

```java
    private static final ThreadLocal<DefaultFlowEngine> CURRENT_ENGINE = new ThreadLocal<>();
    private static final System.Logger LOG = System.getLogger(DefaultFlowEngine.class.getName());

    private final FlowCompiler compiler;
    private final ConditionEvaluator evaluator;
    private final EngineConfig config;
    private final ThreadPoolExecutor workerPool;
    private final Semaphore admissionPermits;
    private final Object registryLock = new Object();
    private final Set<Root> roots = ConcurrentHashMap.newKeySet();
    private volatile Map<String, Definition> registry = Map.of();
    private volatile boolean closed;
```

全文 `WORKER.get()` -> `CURRENT_ENGINE.get()`、`WORKER.set(this)` -> `CURRENT_ENGINE.set(this)`、`WORKER.remove()` -> `CURRENT_ENGINE.remove()`、`pool.` -> `workerPool.`、`permits.` -> `admissionPermits.`。

- [ ] **Step 4: 改两个构造器**

```java
    public DefaultFlowEngine(NodeResolver resolver,ConditionEvaluator evaluator){this(resolver,evaluator,EngineConfig.defaults());}
```

改为：

```java
    public DefaultFlowEngine(NodeResolver resolver, ConditionEvaluator evaluator) {
        this(resolver, evaluator, EngineConfig.defaults());
    }
```

三参构造器改为（线程名前缀 `"flow-worker-"`、`setDaemon(true)`、`AbortPolicy`、空闲超时 `0`、`TimeUnit.MILLISECONDS` **逐字保留**）：

```java
    public DefaultFlowEngine(NodeResolver resolver, ConditionEvaluator evaluator, EngineConfig config) {
        this.evaluator = Objects.requireNonNull(evaluator);
        this.config = Objects.requireNonNull(config);
        compiler = new FlowCompiler(Objects.requireNonNull(resolver), evaluator);
        admissionPermits = new Semaphore(config.maxConcurrentExecutions());
        var index = new AtomicInteger();
        workerPool = new ThreadPoolExecutor(config.workerThreads(), config.workerThreads(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "flow-worker-" + index.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
```

- [ ] **Step 5: 改 `execute` 与 `close` 的 `InterruptedException` catch**

两处 catch 形参 `e` -> `exception`（**不是** `execution`，避免与 `Execution` 混淆）：

```java
                catch(InterruptedException e){interrupted=true;forceTree(root.main,FlowStatus.FAILED,"CALLER_INTERRUPTED");settle(root);}
```

改为：

```java
                } catch (InterruptedException exception) {
                    interrupted = true;
                    forceTree(root.main, FlowStatus.FAILED, "CALLER_INTERRUPTED");
                    settle(root);
                }
```

```java
        while(!roots.isEmpty()&&System.nanoTime()<end){try{Thread.sleep(5);}catch(InterruptedException e){interrupted=true;break;}}
```

改为（`Thread.sleep(5)` 的 `5` 逐字保留）：

```java
        while (!roots.isEmpty() && System.nanoTime() < end) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException exception) {
                interrupted = true;
                break;
            }
        }
```

- [ ] **Step 6: 改 `runNode` 的 catch 与模式变量**

```java
        }catch(Throwable ex){
            root.lock.lock();try{expire(root);fail(n,NodeStatus.FAILED,ex instanceof FlowException f?f.code():"NODE_FAILED",ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage());root.wakeup.signalAll();}finally{root.lock.unlock();}
            if(ex instanceof VirtualMachineError error)throw error;
        }finally{WORKER.remove();}
```

改为（`"NODE_FAILED"`、两个三元的判断顺序与取值、`VirtualMachineError` 重新抛出**逐字保留**；不引入新局部变量，以保证 token 等价）：

```java
        } catch (Throwable failure) {
            root.lock.lock();
            try {
                expire(root);
                fail(node, NodeStatus.FAILED,
                        failure instanceof FlowException flowException ? flowException.code() : "NODE_FAILED",
                        failure.getMessage() == null
                                ? failure.getClass().getSimpleName()
                                : failure.getMessage());
                root.wakeup.signalAll();
            } finally {
                root.lock.unlock();
            }
            if (failure instanceof VirtualMachineError error) {
                throw error;
            }
        } finally {
            CURRENT_ENGINE.remove();
        }
```

- [ ] **Step 7: 改 `close` 的模式变量与 `Work` 释放**

```java
        for(var runnable:pool.shutdownNow())if(runnable instanceof DefaultFlowEngine.Work w){var root=w.node.execution.root;root.lock.lock();try{w.cancel(false);w.release();}finally{root.lock.unlock();}}
```

改为：

```java
        for (var runnable : workerPool.shutdownNow()) {
            if (runnable instanceof DefaultFlowEngine.Work work) {
                var root = work.node.execution.root;
                root.lock.lock();
                try {
                    work.cancel(false);
                    work.release();
                } finally {
                    root.lock.unlock();
                }
            }
        }
```

- [ ] **Step 8: 改其余方法的局部变量名**

逐处应用（**所有错误码、消息文本、阈值、比较方向、方法调用顺序一律不变**）：

| 方法 | 旧 | 新 |
| --- | --- | --- |
| `register` / `registerAll` | `Map<String,Definition> next` 等已合规 | 仅补空格与折行 |
| `registerAll` | `candidates.forEach((id,definition)->{if(next.putIfAbsent(id,definition)!=null)throw ...;});` | lambda 形参已合规；补 `{}` 与拆行 |
| `registerAll` | `Map<String,Integer> depth=new HashMap<>();for(var id:next.keySet())referenceDepth(...)` | 拆成两行 |
| `referenceDepth` | `Definition d=definitions.get(id)` | `Definition definition = definitions.get(id)` |
| `referenceDepth` | `int max=0` / `max=Math.max(...)` / `if(max>config.maxSubflowDepth())` / `memo.put(id,max)` | `int maxDepth = 0` 等，全部 `max` -> `maxDepth` |
| `referenceDepth` | `for(var n:d.nodes.values())if(n.type==Type.CALL_FLOW)max=Math.max(max,1+referenceDepth(n.target,...))` | `for (var node : definition.nodes.values())` + 补 `{}` |
| `execute` | `Root root;` 及后续 | 名字已合规，仅补空格与折行 |
| `Root` 构造器 | `Root(Map<String,Definition> d){definitions=d;}` | `Root(Map<String, Definition> definitions) { this.definitions = definitions; }` 展开为多行 |
| `Execution` 构造器 | `Execution(Root root,Definition d,Object input,Execution parent,RuntimeNode call,long deadline)` | 形参 `d` -> `definition`；体内 `definition=d` -> `this.definition = definition` |
| `Execution` 构造器 | `path=parent==null?d.id:parent.path+"/"+call.spec.id+":"+d.id;` | `d.id` -> `definition.id`（两处） |
| `Execution` 构造器 | `d.ordered.forEach(n->nodes.put(n.id,new RuntimeNode(this,n)));ready.add(nodes.get("start"));` | `definition.ordered.forEach(node -> nodes.put(node.id, new RuntimeNode(this, node)));` 并拆行；`"start"` 逐字保留 |
| `RuntimeNode` 构造器 | `RuntimeNode(Execution e,Node n){execution=e;spec=n;remaining=n.in.size();}` | `RuntimeNode(Execution execution, Node node)` + `remaining = node.incomingEdges.size();` 展开为多行 |
| `pump` | `private boolean pump(Root r)` | `private boolean pump(Root root)`；体内 `r.` -> `root.` |
| `pump` | `for(var e:new ArrayList<>(r.executions))` | `for (var execution : new ArrayList<>(root.executions))` |
| `pump` | `if(e.result!=null\|\|e.forced)continue;` | `execution.result` / `execution.forced` + 补 `{}` |
| `pump` | `int count=e.ready.size();` / `while(count-->0&&!e.stopping)` | `execution.ready` / `execution.stopping` |
| `pump` | `var n=e.ready.remove();if(n.status!=NodeStatus.PENDING\|\|n.submitted)continue;` | `var node = execution.ready.remove();` + 补 `{}` |
| `pump` | `if(!hasSlot(e)){e.ready.add(n);continue;}` | `hasSlot(execution)` / `execution.ready.add(node)` |
| `pump` | `n.submitted=true;adjustSlots(e,1);n.work=new Work(n,context(n));` | `adjustSlots(execution, 1)`，拆成三行 |
| `pump` | `catch(RejectedExecutionException ex){n.work.release();fail(n,NodeStatus.FAILED,"RESOURCE_REJECTED","Worker queue full");}` | `catch (RejectedExecutionException rejection)` + `rejection` 未被使用则保留原样（原代码也未使用 `ex`，**不要**新增 `_` 或改逻辑） |
| `pump` | `case TASK,XOR_SPLIT->{...}` / `case CALL_FLOW->{startChild(n);}` / `default->{n.started=Instant.now();success(n,null,null);}` | 保持箭头 switch，`n` -> `node`，补空格与缩进 |
| `startChild` | `private void startChild(RuntimeNode n){Execution p=n.execution;Root r=p.root;` | `RuntimeNode node` + `Execution parent = node.execution;` + `Root root = parent.root;` |
| `startChild` | `long active=r.executions.stream().filter(e->e.parent!=null&&e.result==null).count();` | `root.executions.stream().filter(execution -> execution.parent != null && execution.result == null)` |
| `startChild` | `if(r.created>=...\|\|active>=...\|\|p.depth>=...)` | `root.created` / `parent.depth` |
| `startChild` | `n.child=new Execution(r,r.definitions.get(n.spec.target),p.input,p,n,n.deadline);r.created++;r.executions.add(n.child);` | `node.child = new Execution(root, root.definitions.get(node.spec.target), parent.input, parent, node, node.deadline);` 并拆成三行 |
| `context` | `private NodeContext context(RuntimeNode n)` | `RuntimeNode node` |
| `context` | `for(String id:n.spec.ancestors){var a=n.execution.nodes.get(id);if(a.status==NodeStatus.PENDING\|\|a.status==NodeStatus.RUNNING)throw new IllegalStateException("Unresolved ancestor "+id);records.put(id,a.record());}` | `for (String id : node.spec.ancestors)` + `var ancestor = node.execution.nodes.get(id);` + 补 `{}` + 拆行；`"Unresolved ancestor "` 逐字保留 |
| `hasSlot` | `private boolean hasSlot(Execution e){for(;e!=null;e=e.parent)if(e.inFlight>=config.maxInFlightPerExecution())return false;return true;}` | `Execution execution` + 补 `{}` + 拆行 |
| `adjustSlots` | `private void adjustSlots(Execution e,int delta){for(;e!=null;e=e.parent)e.inFlight+=delta;}` | `Execution execution` + 补 `{}` + 拆行 |
| `publish` | `for(var edge:from.spec.out)` | `for (var edge : from.spec.outgoingEdges)` |
| `success` / `skip` / `fail` | `RuntimeNode n` | `RuntimeNode node`；体内 `n.` -> `node.` |
| `skip` | `if(n.work!=null)n.work.cancelWork();if(propagate)publish(n,null,true);` | 补 `{}` 并拆行 |
| `fail` | `log("node-failed",n.execution,n,code);stop(n.execution);if(n.work!=null)n.work.cancelWork();` | 补 `{}` 并拆行；`"node-failed"` 逐字保留 |
| `stop` | `private void stop(Execution e){e.stopping=true;for(var n:e.nodes.values())if(n.status==NodeStatus.PENDING)skip(n,"FLOW_STOPPED",false);e.ready.clear();}` | `Execution execution` + `for (var node : execution.nodes.values())` + 补 `{}` + 拆行；`"FLOW_STOPPED"` 逐字保留 |
| `settle` | `var e=root.executions.get(i);if(e.result!=null)continue;` | `var execution = root.executions.get(i);` + 补 `{}` |
| `settle` | `boolean pending=e.nodes.values().stream().anyMatch(n->n.status==...)` | `execution.nodes.values().stream().anyMatch(node -> node.status == ...)` |
| `settle` | `if(status==FlowStatus.SUCCEEDED&&e.nodes.get("finish").status!=NodeStatus.SUCCEEDED){...}` | `execution.nodes.get("finish")`；`"finish"`、`"NO_ACTIVE_PATH"`、`"Finish not reached"` 逐字保留 |
| `settle` | `e.nodes.forEach((id,n)->records.put(id,n.record()));` | `execution.nodes.forEach((id, node) -> records.put(id, node.record()));` |
| `settle` | `for(var x:root.executions)if(descendant(x,e))for(var n:x.nodes.values())if(n.work!=null&&!n.work.exited)unfinished.add(x.path+"/"+n.spec.id+"@"+x.id);` | `for (var candidate : root.executions)` + `descendant(candidate, execution)` + `for (var node : candidate.nodes.values())` + 补 `{}` + 拆行；`"/"`、`"@"` 逐字保留 |
| `settle` | `log("flow-end",e,null,status.name());changed=true;` | `log("flow-end", execution, null, status.name());` 拆行；`"flow-end"` 逐字保留 |
| `settle` | `if(e.call!=null&&!terminal(e.call.status)&&e.parent.result==null){var call=e.call;...}` | `execution.call` / `execution.parent.result`，`var call = execution.call;` |
| `settle` | `"CHILD_FLOW_TIMEOUT"` / `"CHILD_FLOW_FAILED"` / `"Child "+e.definition.id+" "+status` | `execution.definition.id`；三段字符串逐字保留 |
| `descendant` | `private boolean descendant(Execution e,Execution ancestor){for(;e!=null;e=e.parent)if(e==ancestor)return true;return false;}` | `Execution execution` + 补 `{}` + 拆行 |
| `expire` | `private void expire(Root r)` | `expire(Root root)`；体内 `r.` -> `root.` |
| `expire` | `for(var e:r.executions)` / `for(var n:e.nodes.values())` | `for (var execution : root.executions)` / `for (var node : execution.nodes.values())` |
| `expire` | `if(now-e.deadline>=0){forceTree(e,FlowStatus.TIMED_OUT,"FLOW_TIMEOUT");continue;}` | `execution.deadline` / `forceTree(execution, ...)`；`"FLOW_TIMEOUT"` 逐字保留 |
| `expire` | `if(n.status==NodeStatus.RUNNING&&n.spec.type!=Type.CALL_FLOW&&now-n.deadline>=0)fail(n,NodeStatus.TIMED_OUT,"NODE_TIMEOUT","Node deadline exceeded");` | `node.` + 补 `{}`；`"NODE_TIMEOUT"`、`"Node deadline exceeded"` 逐字保留 |
| `forceTree` | `for(var e:ancestor.root.executions)if(e.result==null&&descendant(e,ancestor)){` | `for (var execution : ancestor.root.executions)` + `descendant(execution, ancestor)` |
| `forceTree` | `e.forced=true;e.forcedStatus=status;stop(e);` / `for(var n:e.nodes.values())` | `execution.` + `for (var node : execution.nodes.values())` + 拆行 |
| `forceTree` | `new FlowError(code,"Execution terminated",e.id,n.spec.id,e.path)` | `execution.id` / `node.spec.id` / `execution.path`；`"Execution terminated"` 逐字保留 |
| `waitNanos` | `private long waitNanos(Root r)` | `waitNanos(Root root)`；体内 `r.` -> `root.` |
| `waitNanos` | `long now=System.nanoTime(),wait=TimeUnit.SECONDS.toNanos(1);` | 拆成两行（R3） |
| `waitNanos` | `for(var e:r.executions)if(e.result==null&&!e.forced){wait=Math.min(wait,e.deadline-now);for(var n:e.nodes.values())if(n.status==NodeStatus.RUNNING)wait=Math.min(wait,n.deadline-now);}` | `execution` / `node` + 补 `{}` + 拆行；`toNanos(1)` 的 `1` 与 `Math.max(1,wait)` 的 `1` 逐字保留 |
| `Work` 构造器 | `Work(RuntimeNode n,NodeContext context){super(()->{runNode(n,context);return null;});node=n;}` | `Work(RuntimeNode node, NodeContext context)` + `super(() -> { runNode(node, context); return null; });` 拆行 + `this.node = node;` |
| `Work.run` | `@Override public void run(){try{super.run();}finally{var root=node.execution.root;root.lock.lock();try{release();}finally{root.lock.unlock();}}}` | 注解独占一行 + 补空格 + 逐层展开 |
| `Work.release` | `void release(){if(exited)return;exited=true;adjustSlots(node.execution,-1);node.execution.root.wakeup.signalAll();}` | 补 `{}` + 拆行；`-1` 逐字保留 |
| `Work.cancelWork` | `void cancelWork(){cancel(true);if(pool.remove(this))release();}` | `workerPool.remove(this)` + 补 `{}` + 拆行；`true` 逐字保留 |
| `runNode` | `private void runNode(RuntimeNode n,NodeContext context)` | `RuntimeNode node`；体内 `n.` -> `node.` |
| `runNode` | `Root root=n.execution.root;root.lock.lock();` | 拆成两行 |
| `runNode` | `expire(root);if(n.execution.stopping\|\|n.execution.forced\|\|n.status!=NodeStatus.PENDING)return;` | `node.` + 补 `{}` + 拆行 |
| `runNode` | `n.deadline=Math.min(n.execution.deadline,System.nanoTime()+(n.spec.type==Type.XOR_SPLIT?config.gatewayTimeout():config.nodeTimeout()).toNanos());root.wakeup.signalAll();` | `node.` + 按折行方向规则拆行；`Type.XOR_SPLIT` 与两个 `config.*Timeout()` 调用顺序逐字保留 |
| `runNode` | `log("node-start",n.execution,n,null);` | `log("node-start", node.execution, node, null);`；`"node-start"` 逐字保留 |
| `runNode` | `Object value;String selected=null;` | 拆成两行（R3） |
| `runNode` | `if(n.spec.type==Type.TASK)value=n.spec.bean.execute(context);` | 补 `{}` + 拆行 |
| `runNode` | `Edge match=null,fallback=null;int matches=0;` | 拆成三行（R3） |
| `runNode` | `for(var e:n.spec.out){if(e.fallback()){fallback=e;continue;}boolean yes=evaluator.evaluate(e.condition,context);if(yes){matches++;match=e;}}` | `for (var edge : node.spec.outgoingEdges)` + `boolean matched = evaluator.evaluate(edge.condition, context);` + `if (matched) { matches++; match = edge; }` + 拆行 |
| `runNode` | `if(matches>1)throw new FlowException("CONDITION_CONFLICT","Multiple conditions true at "+n.spec.id);` | 补 `{}` + `node.spec.id`；`"CONDITION_CONFLICT"`、`"Multiple conditions true at "` 逐字保留 |
| `runNode` | `if(match==null)match=fallback;` / `if(match==null)throw new FlowException("NO_MATCHING_BRANCH",n.spec.id);` | 补 `{}` + `node.spec.id`；`"NO_MATCHING_BRANCH"` 逐字保留 |
| `runNode` | `selected=match.id;value=null;` | 拆成两行 |
| `runNode` | `root.lock.lock();try{expire(root);success(n,value,selected);root.wakeup.signalAll();}finally{root.lock.unlock();}` | `success(node, value, selected)` + 逐层展开 |
| `terminal` | `private static boolean terminal(NodeStatus status){return status!=NodeStatus.PENDING&&status!=NodeStatus.RUNNING;}` | 展开为多行 |
| `log` | `private static void log(String event,Execution e,RuntimeNode n,String reason)` | `Execution execution, RuntimeNode node`；体内字符串拼接与 `""`、`" execution="`、`" flow="`、`" node="`、`" reason="` **逐字保留**，按折行方向规则拆行 |
| `close` | `if(WORKER.get()==this)throw new FlowException("REENTRANT_EXECUTION","close from worker is unsupported");` | `CURRENT_ENGINE.get()` + 补 `{}`；`"REENTRANT_EXECUTION"`、`"close from worker is unsupported"` 逐字保留 |
| `close` | `long end=System.nanoTime()+config.closeTimeout().toNanos();boolean interrupted=false;` | 拆成两行（R3） |
| `close` | `for(var root:roots){root.lock.lock();try{forceTree(root.main,FlowStatus.FAILED,"ENGINE_CLOSED");settle(root);root.wakeup.signalAll();}finally{root.lock.unlock();}}` | 逐层展开；`"ENGINE_CLOSED"` 逐字保留 |
| `registerAll` / `execute` | `"ENGINE_CLOSED"`、`"Engine is closed"`、`"DUPLICATE_FLOW"`、`"SUBFLOW_LIMIT_EXCEEDED"`、`"Reference depth exceeded"`、`"FLOW_REFERENCE_CYCLE"`、`" -> "`、`"SUBFLOW_NOT_FOUND"`、`"REENTRANT_EXECUTION"`、`"Use an explicit subflow node"`、`"FLOW_NOT_FOUND"`、`"FLOW_REJECTED"`、`"Root capacity exhausted"`、`"Static depth: "` | **全部逐字保留** |

四个嵌套类 `Root` / `Execution` / `RuntimeNode` / `Work` 里的多变量声明全部按 R3 拆成一行一声明，字段名与初始值不变。典型形态：

```java
    private final class RuntimeNode {

        final Execution execution;
        final Node spec;
        final Set<String> resolved = new HashSet<>();
        int remaining;
        int active;
        NodeStatus status = NodeStatus.PENDING;
        Instant started;
        Instant ended;
        long deadline = Long.MAX_VALUE;
        String skip;
        String selected;
        Object value;
        FlowError error;
        Work work;
        Execution child;
        boolean submitted;

        RuntimeNode(Execution execution, Node node) {
            this.execution = execution;
            spec = node;
            remaining = node.incomingEdges.size();
        }

        NodeRecord record() {
            return new NodeRecord(spec.id, spec.target, spec.type.name(), status,
                    status == NodeStatus.SUCCEEDED, value, skip, error, started, ended, selected);
        }
    }
```

> 字段**顺序可以按上表重排**（`final` 在前、可变在后），这是纯声明顺序调整、不影响任何语义；但如果 token 等价校验因此报 FAIL，就恢复原字段顺序。

- [ ] **Step 9: 编译并跑 core 测试**

Run: `mvn -q -pl flow-engine-core test 2>&1 | grep -E "Tests run:|ERROR|BUILD" | tail -10`

Expected: `Tests run: 98, Failures: 0, Errors: 0, Skipped: 0`，无 `ERROR`。

- [ ] **Step 10: token 等价校验**

```bash
python3 /tmp/style-equiv.py HEAD \
  flow-engine-core/src/main/java/io/github/mchgood/flow/runtime/DefaultFlowEngine.java \
  flow-engine-core/src/main/java/io/github/mchgood/flow/runtime/package-info.java
```

Expected: 除 R3 声明拆分点外 `failed=0`（拆分点按第 0 节协议第 2(a) 条处理并补入 `FOLDS`）。

若 FAIL，按 `first divergence` 定位。改名差异已被词表擦除（`yes`/`matched`、`e`/`execution`/`exception`、`n`/`node` 等均在 Task 1 Step 3 的 JSON 里），不会触发 FAIL，因此分歧点必然落在**语句结构、运算符、数值、字符串或方法名**上：

- 分歧点是字段顺序：恢复原字段声明顺序（Task 5 Step 8 末尾允许的重排若导致 FAIL 就撤销）。
- 分歧点是别处：**修回原语义**，不得加进 `EXEMPT`。
- 若确认是新引入且未登记的标识符，把它作为某条映射的值补进 `/tmp/rename-map.json` 里 `DefaultFlowEngine.java` 的条目后重跑。

- [ ] **Step 11: 局部扫描清零确认**

Run: `python3 /tmp/style-scan.py flow-engine-core/src/main/java/io/github/mchgood/flow/runtime | tail -3`

Expected: 末行 `long=0 multi=0 nobrace=0 star=0`。

- [ ] **Step 12: 提交**

```bash
git add flow-engine-core/src/main/java/io/github/mchgood/flow/runtime
git status --short
git commit -m "style: reformat flow-engine-core runtime per Alibaba conventions"
```

Expected: `git add` 后 `git status --short` 只显示 2 个 `M ` 条目。

---

### Task 6: 整改 core 测试源码

**Files:**
- Modify: `flow-engine-core/src/test/java/io/github/mchgood/flow/architecture/PackageBoundaryTest.java`
- Modify: `flow-engine-core/src/test/java/io/github/mchgood/flow/compiler/CompilerContractTest.java`
- Modify: `flow-engine-core/src/test/java/io/github/mchgood/flow/contract/ValueContractTest.java`
- Modify: `flow-engine-core/src/test/java/io/github/mchgood/flow/node/FlowNodeTypeContractTest.java`
- Modify: `flow-engine-core/src/test/java/io/github/mchgood/flow/runtime/GeneratedDagTest.java`
- Modify: `flow-engine-core/src/test/java/io/github/mchgood/flow/runtime/RuntimeBoundaryTest.java`

**Interfaces:**
- Consumes: Task 2-5 的全部产出。
- Produces: 测试类名、测试方法名、`@Test` / `@ParameterizedTest` / `@CsvSource` / `@MethodSource` / `@TempDir` 注解值、断言参数顺序与消息文本、超时数值、固定随机种子、`0.25` 概率阈值**全部逐字不变**。core 模块测试总数保持 **98**。

- [ ] **Step 1: 确认工作区干净**

Run: `git status --short`

Expected: 无输出。

- [ ] **Step 2: 改 `PackageBoundaryTest.java`**

该文件排版已基本合规，只需五处：

1. lambda `p -> p.toString().endsWith(".java")` -> `path -> path.toString().endsWith(".java")`
2. `import io.github.mchgood.flow.spi.*;` -> 三条具体 import（`CompiledCondition`、`ConditionEvaluator`、`SourceLocation`）
3. `import java.nio.file.*;` -> `import java.nio.file.Files;` + `import java.nio.file.Path;`
4. `import static org.junit.jupiter.api.Assertions.*;` -> `assertFalse` + `assertSame` + `assertThrows` 三条，字典序
5. `import java.util.Set;` 与 `import java.util.regex.Pattern;` 保持，按 `ImportOrder` 分组放到 `java` 组内并保证字典序

正则 `"import\\s+(?:static\\s+)?io\\.github\\.mchgood\\.flow\\.([\\w.]+)"`、`Set.of("api", "node", "spi", "config", "result", "exception")`、`"internal."` / `"runtime."` / `"spring."` / `"internal.compiler."` 前缀、`"import org.springframework."`、文本块里的 Mermaid 定义、`Path.of("src/main/java/io/github/mchgood/flow")` **逐字保留**。

匿名 `ConditionEvaluator` 的 `parse` / `evaluate` 两个方法**补上 `@Override` 且独占一行**（用户裁决：按阿里规约【强制】补齐缺失覆写注解，全文共 11 处，本文件占 2 处）。

- [ ] **Step 3: 改 `CompilerContractTest.java`**

| 旧 | 新 |
| --- | --- |
| `var c=new FlowCompiler(...)` | `var compiler = new FlowCompiler(...)`；后续 `c.compile` -> `compiler.compile` |
| `var g=c.compile("sample",md(...))` | `var definition = compiler.compile("sample", md(...))`；后续 `g.` -> `definition.` |
| `var ex=assertThrows(FlowException.class,...)` | `var exception = assertThrows(FlowException.class, ...)`；后续 `ex.` -> `exception.` |
| `map(n->n.id)` | `map(node -> node.id)` |
| `parse(String text, SourceLocation loc)` | `parse(String text, SourceLocation location)`，方法上方**补 `@Override` 独占一行**（11 处缺失覆写之一） |
| `import io.github.mchgood.flow.spi.*;` | `CompiledCondition` + `ConditionEvaluator` + `SourceLocation` |
| `import org.junit.jupiter.params.provider.*;` | 按实际使用展开（`Arguments`、`CsvSource`、`MethodSource`、`ValueSource` 等，以编译结果为准） |
| `import java.util.*;` | 按实际使用展开 |
| `import static org.junit.jupiter.api.Assertions.*;` | `assertEquals` + `assertNotEquals` + `assertThrows` + `assertTrue` |

`for(int i=0;i<510;i++)body.append(" --> work_").append(i);` 补 `{}` 并拆行，`510` 与 `" --> work_"` 逐字保留。辅助方法 `md(...)` 的**方法名保留**。所有错误码字符串（`"DEPRECATED_BINDING"`、`"UNSUPPORTED_SYNTAX"`、`"INVALID_NODE_ID"` 等）与 Mermaid 定义文本**逐字保留**。

- [ ] **Step 4: 改 `ValueContractTest.java`**

| 旧 | 新 |
| --- | --- |
| `.filter(v -> i!=4 \|\| v!=0)` | `.filter(value -> i != 4 \|\| value != 0)` |
| `.map(v -> Arguments.of(i,v))` | `.map(value -> Arguments.of(i, value))` |
| `.map(d->Arguments.of(i,d))` | `.map(duration -> Arguments.of(i, duration))` |
| `java.util.stream.IntStream.range(...)`（2 处内联 FQN） | 加 `import java.util.stream.IntStream;`，改为 `IntStream.range(...)` |
| `int[] limits={1,1,1,1,1,1,1};limits[index]=value;` | 拆成两行（R2） |
| `for(var d:new Duration[]{Duration.ZERO,Duration.ofNanos(-1),Duration.ofDays(1).plusNanos(1)})assertThrows(...)` | `for (var duration : new Duration[] {Duration.ZERO, Duration.ofNanos(-1), Duration.ofDays(1).plusNanos(1)})` + 补 `{}` + 拆行 |
| `for(int depth:List.of(0,32))assertDoesNotThrow(()->new EngineConfig(1,1,1,1,depth,1,1,Duration.ofDays(1),Duration.ofNanos(1),Duration.ofDays(1),Duration.ofNanos(1)));` | `for (int depth : List.of(0, 32))` + 补 `{}` + 按折行方向拆行 |
| `import io.github.mchgood.flow.result.*;` | `FlowError` + `FlowResult` + `FlowStatus` + `NodeRecord` + `NodeStatus` |
| `import org.junit.jupiter.params.provider.*;` | 按实际使用展开 |
| `import java.time.*;` | `Duration` |
| `import java.util.*;` | 按实际使用展开 |
| `import static org.junit.jupiter.api.Assertions.*;` | `assertDoesNotThrow` + `assertEquals` + `assertFalse` + `assertNull` + `assertSame` + `assertThrows` + `assertTrue` |

`IntStream.range(0,7)` / `range(0,4)` / `Stream.of(-1,0)` / `List.of(0,32)` / `Duration.ofNanos(-1)` / `Duration.ofDays(1).plusNanos(1)` / `new EngineConfig(1,1,1,1,depth,1,1,...)` **所有数值逐字保留**。lambda 里的 `i`（IntStream 索引）在白名单内，保留。

- [ ] **Step 5: 改 `FlowNodeTypeContractTest.java`**

| 旧 | 新 |
| --- | --- |
| `anyMatch(d->d.getKind()==Diagnostic.Kind.ERROR)` | `anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)` |
| `import javax.tools.*;` | `Diagnostic` + `DiagnosticCollector` + `JavaFileObject` + `ToolProvider` |
| `import java.nio.file.*;` | `Files` + `Path` |
| `import static org.junit.jupiter.api.Assertions.*;` | `assertEquals` + `assertNotNull` + `assertTrue` |

`"""..."""` 文本块里生成的 `TypedNode` 源码**逐字不动**（它本身已合规）；`@CsvSource({"123,false", "Integer.toString(123),true"})`、`"Tests require a JDK"`、编译器选项 `"classpath"` / `"-classpath"` / `"-d"` / `"-proc:none"` / `"-Xlint:rawtypes,unchecked"` / `"-Werror"` **逐字保留**。`var compiler` / `Path source` / `var diagnostics` / `var manager` / `String classes` / `boolean succeeded` 均已合规，只补空格与拆行。

- [ ] **Step 6: 改 `GeneratedDagTest.java`**

该文件排版已相对规整，主要是通配符 import 与少量缺大括号：

| 旧 | 新 |
| --- | --- |
| `import io.github.mchgood.flow.spi.*;` | `CompiledCondition` + `ConditionEvaluator` + `SourceLocation` |
| `import java.util.*;` | 按实际使用展开 |
| `import static org.junit.jupiter.api.Assertions.*;` | `assertEquals` + `assertTrue` |
| `for(int j=0;j<i;j++)if(random.nextDouble()<0.25)parents.add(j);` | 补 `{}` 并拆行 |
| `for(int i=0;i<count;i++)if(!hasSuccessor[i])edges.add("work_"+i+" --> finish([f])");` | 补 `{}` 并拆行；`"work_"` 与 `" --> finish([f])"` 逐字保留 |
| `for(int i=0;i<count;i++){assertEquals(1,calls.get(i),"work_"+i);assertEquals(expected[i],result.results().get("work_"+i).value(),"seed="+seed+", work_"+i);}` | 拆成多行，补空格；三处字符串与 `1` 逐字保留 |

**固定随机种子与 `0.25` 概率阈值逐字保留**（AGENTS.md 要求生成型测试用固定种子与独立期望值计算）。`i` / `j` 在白名单内保留。

- [ ] **Step 7: 改 `RuntimeBoundaryTest.java` 的局部变量名**

> **关键约束**：本文件有静态辅助方法 `engine(...)`。若把 `try(var e=engine(...))` 改成 `var engine = engine(...)`，局部变量会遮蔽方法名，导致后续 `engine(Map.of(...))` 被解析为变量而编译失败。因此引擎局部变量统一命名为 **`flowEngine`**（`/tmp/rename-map.json` 里已按此登记）。

| 旧 | 新 |
| --- | --- |
| `try(var e=engine(...))` / `e.register(...)` / `e.execute(...)` / `e.registerAll(...)` | `try (var flowEngine = engine(...))` / `flowEngine.` |
| `var r=e.execute(...)` | `var result = flowEngine.execute(...)`；后续 `r.status()` / `r.results()` / `r.succeeded()` / `r.errors()` / `r.physicalExitUnconfirmed()` -> `result.` |
| `evaluate(CompiledCondition c,NodeContext n)` | `evaluate(CompiledCondition condition, NodeContext context)`，方法上方**补 `@Override` 独占一行** |
| `parse(String t,SourceLocation l)` | `parse(String text, SourceLocation location)`，方法上方**补 `@Override` 独占一行** |
| `catch(InterruptedException x)` | `catch (InterruptedException exception)` |
| `catch(InterruptedException ex)` | `catch (InterruptedException exception)` |
| `var a=callers.submit(attempt);var b=callers.submit(attempt);` | `var first = callers.submit(attempt);` + `var second = callers.submit(attempt);` 两行 |
| `allMatch(n->n.get()==1)` | `allMatch(counter -> counter.get() == 1)` |
| `id->c->calls.incrementAndGet()` | `id -> context -> calls.incrementAndGet()` |
| `c->{if(Boolean.TRUE.equals(c.input()))ref.get().close();return 7;}` | `context -> { if (Boolean.TRUE.equals(context.input())) { ref.get().close(); } return 7; }` 再拆行；`7` 逐字保留 |
| `c->{throw new FlowException("BUSINESS_REJECTED","denied");}` | `context -> { throw new FlowException("BUSINESS_REJECTED", "denied"); }` 拆行 |
| `Map.of("work",c->1)` / `Map.of("work",c->calls.incrementAndGet())` / `Map.of("work",work)` | `context -> 1` 等 |
| `id->{binding.countDown();try{...}catch(InterruptedException x){throw new AssertionError(x);}return c->1;}` | `exception` + `context -> 1`，逐层展开 |

所有 `config(1,1,4,8,64,16)` 形态的资源参数、`Duration.ofSeconds(...)` / `Duration.ofMillis(...)`、错误码字符串、Mermaid 定义**逐字保留**。

- [ ] **Step 8: 改 `RuntimeBoundaryTest.java` 的 import 块**

| 旧 | 新 |
| --- | --- |
| `import io.github.mchgood.flow.node.*;` | `FlowNode` + `NodeContext` |
| `import io.github.mchgood.flow.result.*;` | `FlowStatus` + `NodeStatus`（以编译结果为准） |
| `import io.github.mchgood.flow.spi.*;` | `CompiledCondition` + `ConditionEvaluator` + `SourceLocation` |
| `import org.junit.jupiter.api.*;` | 按实际使用展开（`Test`、`Timeout` 等） |
| `import java.util.*;` / `java.util.concurrent.*;` / `java.util.concurrent.atomic.*;` | 按实际使用展开 |
| `import static org.junit.jupiter.api.Assertions.*;` | `assertEquals` + `assertNotEquals` + `assertThrows` + `assertTrue` |

- [ ] **Step 9: 折行 `RuntimeBoundaryTest.java` 的 3 处超长 Mermaid 字面量**

原第 76、125、140 行，长度分别 122 / 277 / 158 字符。按字面量内的 `\n` 边界用 `+` 拼接折行，每段缩进 8 空格（`lineWrappingIndentation=4` 的两级），例如：

```java
            e.register("flow",md("start([s]) --> child_one[[\"child\"]]\nstart --> child_two[[\"child\"]]\n..."));
```

改为：

```java
            flowEngine.register("flow", md("start([s]) --> child_one[[\"child\"]]\n"
                    + "start --> child_two[[\"child\"]]\n"
                    + "..."));
```

折行后必须逐字节等于原字面量。验证：

```bash
python3 - <<'PY'
import pathlib, re, subprocess
path = 'flow-engine-core/src/test/java/io/github/mchgood/flow/runtime/RuntimeBoundaryTest.java'
old = subprocess.run(['git', 'show', 'HEAD:%s' % path], capture_output=True, text=True, check=True).stdout
new = pathlib.Path(path).read_text(encoding='utf-8')
LIT = re.compile(r'"(?:[^"\\]|\\.)*\\n(?:[^"\\]|\\.)*"')
old_long = sorted(s for s in LIT.findall(old) if len(s) > 100)
joined = re.sub(r'"\s*\n\s*\+\s*"', '', new)
new_long = sorted(s for s in LIT.findall(joined) if len(s) > 100)
print('old long literals:', len(old_long))
print('new long literals:', len(new_long))
print('IDENTICAL' if old_long == new_long else 'MISMATCH')
for a, b in zip(old_long, new_long):
    if a != b:
        print('  OLD:', a[:140])
        print('  NEW:', b[:140])
PY
```

Expected: 打印 `IDENTICAL`，两个计数均为 **3**。若 `MISMATCH`，脚本会打印首个不一致的字面量前 140 字符，据此修正拼接点。

- [ ] **Step 10: 跑 core 全量测试**

Run: `mvn -q -pl flow-engine-core test 2>&1 | grep -E "Tests run:|ERROR|BUILD" | tail -10`

Expected: `Tests run: 98, Failures: 0, Errors: 0, Skipped: 0`。**测试数必须仍是 98**——少一个就说明改坏了 `@ParameterizedTest` 的数据源或 `@CsvSource`。

- [ ] **Step 11: token 等价校验**

```bash
python3 /tmp/style-equiv.py HEAD $(git diff --name-only HEAD -- 'flow-engine-core/src/test')
```

Expected: 一条 `EQUIV EXEMPT ...RuntimeBoundaryTest.java` + 除 R3 声明拆分点外 `failed=0`（拆分点按第 0 节协议第 2(a) 条处理）。

- [ ] **Step 12: 局部扫描清零确认**

Run: `python3 /tmp/style-scan.py flow-engine-core/src/test | tail -3`

Expected: 末行 `long=0 multi=0 nobrace=0 star=0`。

- [ ] **Step 13: 提交**

```bash
git add flow-engine-core/src/test
git status --short
git commit -m "style: reformat flow-engine-core tests per Alibaba conventions"
```

Expected: `git add` 后 `git status --short` 只显示 6 个 `M ` 条目。

---

### Task 7: 整改 spring main 源码

**Files:**
- Modify: `flow-engine-spring/src/main/java/io/github/mchgood/flow/spring/SpelConditionEvaluator.java`
- Modify: `flow-engine-spring/src/main/java/io/github/mchgood/flow/spring/SpringNodeResolver.java`
- Modify: `flow-engine-spring/src/main/java/io/github/mchgood/flow/spring/package-info.java`

**Interfaces:**
- Consumes: Task 3 的契约包签名（未变）。
- Produces（Task 8 依赖）：`SpelConditionEvaluator()` 无参构造器、`parse(String text, SourceLocation location)` : `CompiledCondition`、`evaluate(CompiledCondition condition, NodeContext data)` : `boolean`；`SpringNodeResolver(ConfigurableListableBeanFactory factory)`、`resolve(String id)` : `FlowNode<?>`。签名**逐字不变**。`ALLOWED` -> `ALLOWED_NODE_KINDS` 与私有 record `Parsed` 均为 `private`，无外部引用。

- [ ] **Step 1: 确认工作区干净**

Run: `git status --short`

Expected: 无输出。

- [ ] **Step 2: 改 `SpelConditionEvaluator.java` 的常量名**

```java
    private static final Set<String> ALLOWED=Set.of("CompoundExpression","VariableReference","PropertyOrFieldReference","Indexer",
        "StringLiteral","BooleanLiteral","NullLiteral","IntLiteral","LongLiteral","RealLiteral","FloatLiteral",
        "OpAnd","OpOr","OperatorNot","OpEQ","OpNE","OpLT","OpLE","OpGT","OpGE",
        "OpPlus","OpMinus","OpMultiply","OpDivide","OpModulus","Ternary","Elvis");
```

改为（**27 个节点种类字符串逐字保留、顺序不变**）：

```java
    private static final Set<String> ALLOWED_NODE_KINDS = Set.of(
            "CompoundExpression", "VariableReference", "PropertyOrFieldReference", "Indexer",
            "StringLiteral", "BooleanLiteral", "NullLiteral", "IntLiteral", "LongLiteral",
            "RealLiteral", "FloatLiteral",
            "OpAnd", "OpOr", "OperatorNot", "OpEQ", "OpNE", "OpLT", "OpLE", "OpGT", "OpGE",
            "OpPlus", "OpMinus", "OpMultiply", "OpDivide", "OpModulus", "Ternary", "Elvis");
```

全文 `ALLOWED.contains(kind)` -> `ALLOWED_NODE_KINDS.contains(kind)`（1 处）。

- [ ] **Step 3: 替换 `SpelConditionEvaluator.java` 的 import 块**

```java
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.spi.CompiledCondition;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.SourceLocation;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
```

> 以「编译无 `cannot find symbol`」为准多退少补。

- [ ] **Step 4: 改 `parse` 方法**

```java
    @Override public CompiledCondition parse(String text,SourceLocation location){
        if(text==null||text.length()>2048)throw new FlowException("EXPRESSION_LIMIT",location.toString());
        try {
            var expression=(SpelExpression)new SpelExpressionParser().parseExpression(text);
            validate(expression.getAST(),0);
            return new Parsed(expression,location);
        }catch(FlowException e){throw e;}catch(RuntimeException e){throw new FlowException("EXPRESSION_SYNTAX_ERROR",location+" Invalid SpEL",e);}
    }
```

改为（`2048`、错误码、消息文本、catch 顺序**逐字保留**）：

```java
    @Override
    public CompiledCondition parse(String text, SourceLocation location) {
        if (text == null || text.length() > 2048) {
            throw new FlowException("EXPRESSION_LIMIT", location.toString());
        }
        try {
            var expression = (SpelExpression) new SpelExpressionParser().parseExpression(text);
            validate(expression.getAST(), 0);
            return new Parsed(expression, location);
        } catch (FlowException flowException) {
            throw flowException;
        } catch (RuntimeException runtimeException) {
            throw new FlowException("EXPRESSION_SYNTAX_ERROR", location + " Invalid SpEL", runtimeException);
        }
    }
```

私有 record 声明改为：

```java
    private record Parsed(SpelExpression expression, SourceLocation location) implements CompiledCondition {
    }
```

- [ ] **Step 5: 改 `validate` 方法**

`if(depth>32||!ALLOWED.contains(kind))throw ...` 等 5 处单语句 `if` 全部补 `{}` 并拆行；`for(int i=0;i<node.getChildCount();i++)validate(node.getChild(i),depth+1);` 补 `{}` 并拆行（`i` 在白名单内保留）。

`32` 阈值、`"VariableReference"` / `"PropertyOrFieldReference"` / `"CompoundExpression"` / `"Indexer"` / `"StringLiteral"` 五个字面量、`Set.of("#input","#results")`、`Set.of("class","classLoader","declaringClass")`、`"Use a literal ancestor ID"`、`"EXPRESSION_FORBIDDEN"`、`node.getChildCount()>1`、`node.getChild(0)` / `getChild(1)`、`depth+1` **逐字保留**。

- [ ] **Step 6: 改 `evaluate` 方法**

| 旧 | 新 |
| --- | --- |
| `var parsed=(Parsed)condition;` | `var parsed = (Parsed) condition;`（名字已合规） |
| `data.ancestors().forEach((id,n)->{...})` | `data.ancestors().forEach((id, nodeRecord) -> {...})` |
| `Map<String,Object> out=new LinkedHashMap<>();out.put("status",n.status().name());out.put("present",n.present());out.put("value",n.value());out.put("skipReason",n.skipReason());` | `out` -> `view`，`n.` -> `nodeRecord.`，拆成 5 行（R2/R3） |
| `results.put(id,Collections.unmodifiableMap(out));` | `results.put(id, Collections.unmodifiableMap(view));` |
| `Map<String,Object> guarded=new AbstractMap<>(){...}` | 补空格；匿名类两个方法体展开为多行 |
| `public Set<Entry<String,Object>> entrySet(){return Collections.unmodifiableMap(results).entrySet();}` | 方法上方**补 `@Override` 独占一行**，方法体展开为多行 |
| `@Override public Object get(Object id){if(!results.containsKey(id))throw new FlowException("CONTEXT_ACCESS_DENIED","Not an ancestor: "+id);return results.get(id);}` | 注解独占一行 + 补 `{}` + 拆行 |
| `var context=SimpleEvaluationContext.forPropertyAccessors(new ReadOnlyMapAccessor(),DataBindingPropertyAccessor.forReadOnlyAccess()).withAssignmentDisabled().build();` | 按折行方向规则拆行（点号留上一行末尾） |
| `context.setVariable("input",data.input());context.setVariable("results",guarded);` | 拆成两行 |
| `Object value=parsed.expression.getValue(context);` | 仅补空格 |
| `if(!(value instanceof Boolean result))throw new FlowException("EXPRESSION_TYPE_ERROR",parsed.location+" Expected Boolean");` | 补 `{}` + 拆行；模式变量 `result` 已合规 |
| `catch(FlowException e){throw e;}catch(RuntimeException e){throw new FlowException("EXPRESSION_EVALUATION_ERROR",parsed.location+" SpEL evaluation failed",e);}` | `flowException` / `runtimeException`，展开为多行 |

六个键名 `"status"` / `"present"` / `"value"` / `"skipReason"` / `"input"` / `"results"` 与三个错误码 `"CONTEXT_ACCESS_DENIED"` / `"EXPRESSION_TYPE_ERROR"` / `"EXPRESSION_EVALUATION_ERROR"`、两句消息文本 **逐字保留**。

- [ ] **Step 7: 改 `ReadOnlyMapAccessor` 内部类**

```java
    private static final class ReadOnlyMapAccessor implements PropertyAccessor {
        public Class<?>[] getSpecificTargetClasses(){return new Class<?>[]{Map.class};}
        public boolean canRead(EvaluationContext c,Object target,String name){return target instanceof Map<?,?> map&&map.containsKey(name);}
        public TypedValue read(EvaluationContext c,Object target,String name){return new TypedValue(((Map<?,?>)target).get(name));}
        public boolean canWrite(EvaluationContext c,Object target,String name){return false;}
        public void write(EvaluationContext c,Object target,String name,Object value){throw new FlowException("EXPRESSION_FORBIDDEN","Read only");}
    }
```

改为（形参 `c` -> `context`；五个方法体逻辑逐字不变；五个方法全部**补上 `@Override` 且独占一行**——用户裁决补齐缺失覆写注解）：

```java
    private static final class ReadOnlyMapAccessor implements PropertyAccessor {

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return new Class<?>[] {Map.class};
        }

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) {
            return target instanceof Map<?, ?> map && map.containsKey(name);
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) {
            return new TypedValue(((Map<?, ?>) target).get(name));
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return false;
        }

        @Override
        public void write(EvaluationContext context, Object target, String name, Object value) {
            throw new FlowException("EXPRESSION_FORBIDDEN", "Read only");
        }
    }
```

- [ ] **Step 8: 改 `SpringNodeResolver.java`**

import 块按分组顺序重排（现有 `ConfigurableListableBeanFactory` -> `ScopedProxyUtils` -> `BeansException` 不是字典序），目标：

```java
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.node.FlowNode;
import io.github.mchgood.flow.spi.NodeResolver;

import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
```

构造器与方法体改为：

```java
    public SpringNodeResolver(ConfigurableListableBeanFactory factory) {
        this.factory = factory;
    }
```

```java
    @Override
    public FlowNode<?> resolve(String id) {
        try {
            String target = ScopedProxyUtils.getTargetBeanName(id);
            if (!factory.isSingleton(id)
                    || (factory.containsBean(target) && !factory.isSingleton(target))) {
                throw new FlowException("BEAN_SCOPE_UNSUPPORTED", id);
            }
            return factory.getBean(id, FlowNode.class);
        } catch (FlowException flowException) {
            throw flowException;
        } catch (BeansException beansException) {
            throw new FlowException("BEAN_BINDING_ERROR", id, beansException);
        }
    }
```

`FlowNode.class` 这个类字面量保留原样（AGENTS.md 明确允许容器查找用 raw 类字面量）。两个错误码逐字保留。

- [ ] **Step 9: 编译并跑 spring 测试**

Run: `mvn -q -pl flow-engine-spring -am test 2>&1 | grep -E "Tests run:|ERROR|BUILD" | tail -8`

Expected: core `Tests run: 98` 与 spring `Tests run: 84`，均 `Failures: 0, Errors: 0`，无 `ERROR`。

- [ ] **Step 10: token 等价校验**

```bash
python3 /tmp/style-equiv.py HEAD $(git diff --name-only HEAD -- 'flow-engine-spring/src/main')
```

Expected: 除 R3 声明拆分点外 `failed=0`（拆分点按第 0 节协议第 2(a) 条处理）。

- [ ] **Step 11: 局部扫描清零确认**

Run: `python3 /tmp/style-scan.py flow-engine-spring/src/main | tail -3`

Expected: 末行 `long=0 multi=0 nobrace=0 star=0`。

- [ ] **Step 12: 提交**

```bash
git add flow-engine-spring/src/main
git status --short
git commit -m "style: reformat flow-engine-spring sources per Alibaba conventions"
```

Expected: `git add` 后 `git status --short` 只显示 3 个 `M ` 条目。

---

### Task 8: 整改 spring 测试源码

**Files:**
- Modify: `flow-engine-spring/src/test/java/io/github/mchgood/flow/FlowEngineTest.java`
- Modify: `flow-engine-spring/src/test/java/io/github/mchgood/flow/spring/GenericNodeIntegrationTest.java`
- Modify: `flow-engine-spring/src/test/java/io/github/mchgood/flow/spring/SpelContractTest.java`
- Modify: `flow-engine-spring/src/test/java/io/github/mchgood/flow/spring/SpringResolverContractTest.java`

**Interfaces:**
- Consumes: Task 2-7 的全部产出。
- Produces: 测试类名、方法名、注解值、断言参数与消息文本、超时数值、latch 计数、线程数**全部逐字不变**。spring 模块测试总数保持 **84**。

- [ ] **Step 1: 确认工作区干净**

Run: `git status --short`

Expected: 无输出。

- [ ] **Step 2: 改 `FlowEngineTest.java` 的短名**

> **关键约束**：与 Task 6 Step 7 同理，本文件有静态辅助方法 `engine(...)`，引擎局部变量必须命名 **`flowEngine`**，不能叫 `engine`。

| 旧 | 新 |
| --- | --- |
| `try(var e=engine(...))` / `e.register` / `e.execute` | `try (var flowEngine = engine(...))` / `flowEngine.` |
| `id->{var n=beans.get(id);if(n==null)throw new FlowException("BEAN_NOT_FOUND",id);return n;}` | `id -> { var node = beans.get(id); if (node == null) { throw new FlowException("BEAN_NOT_FOUND", id); } return node; }` 再按 R1/R2 拆行 |
| `c->1` / `c->{...}` / `c->"ok"` | `context -> 1` 等 |
| `var a=e.execute("choice",Map.of("amount",10))` | `var lowAmount = flowEngine.execute("choice", Map.of("amount", 10))`；后续 `a.` -> `lowAmount.` |
| `var b=e.execute("choice",Map.of("amount",2000))` | `var highAmount = flowEngine.execute("choice", Map.of("amount", 2000))`；后续 `b.` -> `highAmount.` |
| `var r=...` | `var result = ...`；后续 `r.` -> `result.` |
| `var x=assertThrows(FlowException.class,...)` | `var exception = assertThrows(FlowException.class, ...)`；`x.getMessage()` -> `exception.getMessage()` |
| `ProxyFactory pf=new ProxyFactory((FlowNode<?>)c->"ok")` | `ProxyFactory proxyFactory = new ProxyFactory((FlowNode<?>) context -> "ok")`；`pf.addAdvice` / `pf.getProxy()` -> `proxyFactory.` |
| `(org.aopalliance.intercept.MethodInterceptor)inv->{invoked.incrementAndGet();return inv.proceed();}` | 加 `import org.aopalliance.intercept.MethodInterceptor;`，改为 `(MethodInterceptor) invocation -> { invoked.incrementAndGet(); return invocation.proceed(); }` 并拆行 |
| `var fs=new ArrayList<Future<FlowResult>>();for(int i=0;i<4;i++){int value=i;fs.add(callers.submit(()->e.execute("flow",value)));}for(int i=0;i<4;i++)assertEquals(i,fs.get(i).get().results().get("work").value());` | `fs` -> `futures`，`e.` -> `flowEngine.`，整块按 R1/R2 展开；`4`、`"flow"`、`"work"` 逐字保留；`i` 保留 |
| `ctx.registerBean("work",FlowNode.class,()->c->1,bd->bd.setScope("prototype"))` | `() -> context -> 1` 与 `definition -> definition.setScope("prototype")`；`"work"`、`"prototype"` 逐字保留 |
| `catch(InterruptedException ex){throw new AssertionError(ex);}` | `catch (InterruptedException exception) { throw new AssertionError(exception); }` 拆行 |
| `catch(InterruptedException x)` | `catch (InterruptedException exception)` |
| `catch(Exception x)` / 其他 `x` catch | `catch (... exception)` |

`10` / `2000` / `4` / `2` / `3` 等所有数值、`"BEAN_NOT_FOUND"` / `"MERMAID_BLOCK_COUNT"` / `"BUSINESS_REJECTED"` / `"flow:5:"` 等所有字符串、`Duration.ofSeconds(2)` / `Duration.ofSeconds(3)`、Bean 名 `"a"` / `"b"` / `"check"` / `"save"` / `"endTask"` / `"work"` / `"slow"` / `"fast"` / `"enrich"` **逐字保留**。

- [ ] **Step 3: 改 `FlowEngineTest.java` 的 import 块**

`import org.junit.jupiter.api.*;` / `java.time.*` / `java.util.*` / `java.util.concurrent.*` / `java.util.concurrent.atomic.*` 按实际使用展开；`import static org.junit.jupiter.api.Assertions.*;` 展开为 `assertEquals` + `assertFalse` + `assertNotEquals` + `assertNull` + `assertThrows` + `assertTrue`（字典序）；新增 `import org.aopalliance.intercept.MethodInterceptor;`（放 `org` 组，字典序在 `org.junit` 之前、`org.springframework` 之前）。

- [ ] **Step 4: 折行 `FlowEngineTest.java` 的 5 处超长 Mermaid 字面量**

原第 41、50、57、103、178 行，长度分别 105 / 142 / 123 / 125 / 269。按字面量内的 `\n` 边界用 `+` 拼接折行（同 Task 6 Step 9 的形态）。验证：

```bash
python3 - <<'PY'
import pathlib, re, subprocess
path = 'flow-engine-spring/src/test/java/io/github/mchgood/flow/FlowEngineTest.java'
old = subprocess.run(['git', 'show', 'HEAD:%s' % path], capture_output=True, text=True, check=True).stdout
new = pathlib.Path(path).read_text(encoding='utf-8')
LIT = re.compile(r'"(?:[^"\\]|\\.)*\\n(?:[^"\\]|\\.)*"')
old_long = sorted(s for s in LIT.findall(old) if len(s) > 100)
joined = re.sub(r'"\s*\n\s*\+\s*"', '', new)
new_long = sorted(s for s in LIT.findall(joined) if len(s) > 100)
print('old long literals:', len(old_long))
print('new long literals:', len(new_long))
print('IDENTICAL' if old_long == new_long else 'MISMATCH')
for a, b in zip(old_long, new_long):
    if a != b:
        print('  OLD:', a[:140])
        print('  NEW:', b[:140])
PY
```

Expected: 打印 `IDENTICAL`，两个计数均为 **5**。

- [ ] **Step 5: 改 `GenericNodeIntegrationTest.java`**

| 旧 | 新 |
| --- | --- |
| `import org.springframework.context.annotation.*;` | 按实际使用展开 |
| `import static org.junit.jupiter.api.Assertions.*;` | `assertEquals` + `assertFalse` + `assertNull` + `assertSame` + `assertTrue` |
| `proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation->{...})` | 加 `import org.aopalliance.intercept.MethodInterceptor;`，去掉内联 FQN，lambda 体拆行 |

无 <=2 字符标识符，其余按 R1-R5、R8-R10 排版。内部 record `ValidationResult(boolean valid)` 保持 `{}` 单行空体形态。

- [ ] **Step 6: 改 `SpelContractTest.java`**

| 旧 | 新 |
| --- | --- |
| `for(int i=0;i<results.size();i++)assertEquals(i==10,results.get(i).get(3,TimeUnit.SECONDS));` | 补 `{}` 拆行；`i`、`10`、`3` 逐字保留 |
| `import io.github.mchgood.flow.result.*;` | `NodeRecord` + `NodeStatus` |
| `import org.junit.jupiter.params.provider.*;` | 按实际使用展开 |
| `import java.util.*;` / `java.util.concurrent.*;` | 按实际使用展开 |
| `import static org.junit.jupiter.api.Assertions.*;` | `assertEquals` + `assertFalse` + `assertThrows` + `assertTrue` |

所有 SpEL 表达式字符串、错误码字符串、祖先 ID 字面量**逐字保留**（它们是白名单机制的测试数据）。

- [ ] **Step 7: 改 `SpringResolverContractTest.java`**

| 旧 | 新 |
| --- | --- |
| `for(var id:new String[]{"missing","wrong"})assertEquals("BEAN_BINDING_ERROR",assertThrows(FlowException.class,()->resolver.resolve(id)).code());` | 补 `{}` 拆行；`id`、`"missing"`、`"wrong"`、`"BEAN_BINDING_ERROR"` 逐字保留 |
| `ctx.registerBean("work",FlowNode.class,()->c->1)` | `() -> context -> 1`；`c` 是 curried lambda 的 `FlowNode` 参数，不在白名单内 |
| `FlowNode<?> work() {return context->1;}` | 名字已合规，仅补空格 |
| `import org.springframework.context.annotation.*;` | 按实际使用展开 |
| `import static org.junit.jupiter.api.Assertions.*;` | `assertEquals` + `assertSame` + `assertThrows` + `assertTrue` |

- [ ] **Step 8: 跑 spring 测试**

Run: `mvn -q -pl flow-engine-spring -am test 2>&1 | grep -E "Tests run:|ERROR|BUILD" | tail -8`

Expected: core `Tests run: 98` 与 spring `Tests run: 84`，均 `Failures: 0, Errors: 0`，无 `ERROR`。

- [ ] **Step 9: token 等价校验**

```bash
python3 /tmp/style-equiv.py HEAD $(git diff --name-only HEAD -- 'flow-engine-spring/src/test')
```

Expected: 一条 `EQUIV EXEMPT ...FlowEngineTest.java` + 除 R3 声明拆分点外 `failed=0`（拆分点按第 0 节协议第 2(a) 条处理）。

- [ ] **Step 10: 局部扫描清零确认**

Run: `python3 /tmp/style-scan.py flow-engine-spring/src/test | tail -3`

Expected: 末行 `long=0 multi=0 nobrace=0 star=0`。

- [ ] **Step 11: 提交**

```bash
git add flow-engine-spring/src/test
git status --short
git commit -m "style: reformat flow-engine-spring tests per Alibaba conventions"
```

Expected: `git add` 后 `git status --short` 只显示 4 个 `M ` 条目。

---

### Task 9: 整改 starter 与 examples 源码

**Files:**
- Modify: `flow-engine-spring-boot-starter/src/main/java/io/github/mchgood/flow/boot/autoconfigure/FlowEngineAutoConfiguration.java`
- Modify: `flow-engine-spring-boot-starter/src/main/java/io/github/mchgood/flow/boot/autoconfigure/FlowEngineProperties.java`
- Modify: `flow-engine-spring-boot-starter/src/main/java/io/github/mchgood/flow/boot/autoconfigure/package-info.java`
- Modify: `flow-engine-spring-boot-starter/src/test/java/io/github/mchgood/flow/boot/autoconfigure/FlowEngineAutoConfigurationTest.java`
- Modify: `flow-engine-examples/src/main/java/io/github/mchgood/flow/OrderExample.java`
- Modify: `flow-engine-examples/src/test/java/io/github/mchgood/flow/OrderExampleTest.java`

**Interfaces:**
- Consumes: Task 2-8 的全部产出。
- Produces: `FlowEngineProperties` 的所有 getter/setter 名与 `@ConfigurationProperties` 前缀 `flow-engine` **逐字不变**（改名会破坏配置文件绑定契约）；`FlowEngineAutoConfiguration` 的 Bean 方法名 `flowEngine` / `flowEngineConfig` 与其 `@ConditionalOnMissingBean` / `@AutoConfiguration` / `destroyMethod` 语义**逐字不变**；`OrderExample` 的 Bean 名与流程定义**逐字不变**（`docs/quick-start.md` 引用了这个示例）。测试总数 starter **22** + examples **1**。

- [ ] **Step 1: 确认工作区干净**

Run: `git status --short`

Expected: 无输出。

- [ ] **Step 2: 改 `FlowEngineProperties.java`**

该文件 255 行、仅 3 行超 120 字符、无短名。按 R1-R5、R8-R10 排版：

- 每个 getter/setter 的单行方法体展开为多行
- 注解（`@ConfigurationProperties` 等）独占一行
- record / 内部类空体写作 `{}`
- 三处超长行按折行方向规则拆行

**所有属性名、默认值、`Duration` 单位、校验条件、`@ConfigurationProperties` 前缀逐字保留**——这些直接映射 `application.properties` 的键与 `docs/spring-boot.md` 的文档，改一个字符就是破坏性变更。

- [ ] **Step 3: 改 `FlowEngineAutoConfiguration.java`**

- Bean 方法体展开为多行
- `@Bean` / `@ConditionalOnMissingBean` / `@EnableConfigurationProperties` / `@AutoConfiguration` 等注解各自独占一行
- 单语句 `if` 补 `{}`
- 展开通配符 import（若有）

**`destroyMethod`、Bean 方法名、条件注解的 value、`FlowEngineProperties` -> `EngineConfig` 的映射逻辑逐字保留**（AGENTS.md 要求自动配置对用户 Bean 退让、上下文关闭时释放引擎、不自动加载或执行流程）。

- [ ] **Step 4: 改 `OrderExample.java`**

- `import org.springframework.context.annotation.*;` 与 `import java.util.*;` 按实际使用展开
- 216 字符的长行按折行方向规则拆行
- 单语句 `if` 补 `{}`
- `@Bean` / `@Component` / `@Configuration` / `@SpringBootApplication` 等注解独占一行
- `result.results().forEach((id,n)->...)` 里的 `n` -> `nodeRecord`（`NodeRecord`，`id` 在白名单内保留）；各 `@Bean` 方法里的 `ctx` 为 3 字符，**保留**

**流程 Markdown 文本块、Mermaid 定义、Bean 名、`System.out.println` 的输出文本逐字保留**。

- [ ] **Step 5: 改 `OrderExampleTest.java`**

| 旧 | 新 |
| --- | --- |
| `var r=...` | `var result = ...`；后续 `r.` -> `result.` |
| `import static org.junit.jupiter.api.Assertions.*;` | `assertEquals` + `assertTrue` |

341 字符的长行按折行方向规则拆行。

- [ ] **Step 6: 改 `FlowEngineAutoConfigurationTest.java`**

195 行、3 行超 120 字符、无短名。按 R1-R5、R8-R10 排版。`static class BootHost {}` 这类空体保持 `{}` 单行形态（Task 10 的 `RightCurly` 用 `alone_or_singleline` 容纳它）。

**所有 `@Test` 方法名、断言的错误消息文本（`"Invalid capacities"` / `"Invalid timeout"`）、Bean 名 `"first"` / `"second"`、属性键名逐字保留**——这些是负向测试断言的可观察副作用（AGENTS.md 要求负向测试断言错误码与可观察副作用）。

- [ ] **Step 7: 跑全量测试**

Run: `mvn -B test 2>&1 | grep -E "Tests run:.*Failures: [0-9]+, Errors|BUILD" | tail -8`

Expected: 四个模块分别 `Tests run: 98` / `84` / `22` / `1`，全部 `Failures: 0, Errors: 0`，末行 `BUILD SUCCESS`。合计 **205**。

- [ ] **Step 8: token 等价校验**

```bash
python3 /tmp/style-equiv.py HEAD $(git diff --name-only HEAD -- 'flow-engine-spring-boot-starter' 'flow-engine-examples')
```

Expected: 除 R3 声明拆分点外 `failed=0`（拆分点按第 0 节协议第 2(a) 条处理）。

- [ ] **Step 9: 全项目扫描清零确认**

Run: `python3 /tmp/style-scan.py . | tail -3`

Expected: 末行 `files=50 long=0 multi=0 star=0`，且 `nobrace` 较整改前大幅下降（允许非零）。

这是核心验收点之一；**大括号的权威证明是 Task 10 checkstyle 的 `NeedBraces` 零违规**，本计数器只提供趋势。

- [ ] **Step 10: 提交**

```bash
git add flow-engine-spring-boot-starter flow-engine-examples
git status --short
git commit -m "style: reformat starter and examples per Alibaba conventions"
```

Expected: `git add` 后 `git status --short` 只显示 6 个 `M ` 条目。

---

### Task 10: 落 checkstyle 闸门

**Files:**
- Create: `config/checkstyle/checkstyle.xml`
- Modify: `pom.xml`（在 `<build><plugins>` 内、`maven-surefire-plugin` 之后追加插件）
- Modify: `AGENTS.md`（Testing expectations 段落追加一句）

**Interfaces:**
- Consumes: Task 2-9 产出的已合规代码。
- Produces: `mvn verify` 新增 `checkstyle-check` execution，违规即失败。CI 的 `mvn -B verify` 自动执行闸门，`.github/workflows/ci.yml` **无需改动**。

- [ ] **Step 1: 确认工作区干净**

Run: `git status --short`

Expected: 无输出。

- [ ] **Step 2: 创建 `config/checkstyle/checkstyle.xml`**

```bash
mkdir -p config/checkstyle
```

写入下列完整内容：

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC
        "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
        "https://checkstyle.org/dtds/configuration_1_3.dtd">

<!--
  阿里巴巴 Java 开发手册的格式与命名子集。

  只覆盖可机械判定的排版与标识符规则；异常、日志、并发、集合、Javadoc 等语义规约
  不在本闸门范围内（见设计文档 2.2 非目标）。

  命名白名单仅 i / j / k（循环下标与 lambda 参数）、id、to（领域词）。
  规则依据：docs/superpowers/specs/2026-09-30-alibaba-code-style-remediation-design.md 第 4、5 节。
-->
<module name="Checker">
    <property name="charset" value="UTF-8"/>
    <property name="severity" value="warning"/>
    <property name="fileExtensions" value="java"/>

    <module name="FileTabCharacter">
        <property name="eachLine" value="true"/>
    </module>
    <module name="NewlineAtEndOfFile">
        <property name="lineSeparator" value="lf"/>
    </module>
    <module name="LineLength">
        <property name="max" value="120"/>
        <property name="ignorePattern" value="^(package|import) .*|.*\bhttps?://\S*"/>
    </module>

    <module name="TreeWalker">

        <!-- import 规约 -->
        <module name="AvoidStarImport">
            <property name="allowClassImports" value="false"/>
            <property name="allowStaticMemberImports" value="false"/>
        </module>
        <module name="RedundantImport"/>
        <module name="UnusedImports">
            <property name="processJavadoc" value="true"/>
        </module>
        <module name="IllegalImport"/>
        <module name="ImportOrder">
            <property name="option" value="bottom"/>
            <property name="groups" value="io.github.mchgood,org,com,java,javax"/>
            <property name="separated" value="true"/>
            <property name="ordered" value="true"/>
            <property name="caseSensitive" value="true"/>
            <property name="sortStaticImportsAlphabetically" value="true"/>
        </module>

        <!-- 大括号与块 -->
        <module name="NeedBraces">
            <property name="allowSingleLineStatement" value="false"/>
        </module>
        <module name="LeftCurly">
            <property name="option" value="eol"/>
        </module>
        <module name="RightCurly">
            <property name="option" value="same"/>
            <property name="tokens"
                      value="LITERAL_TRY,LITERAL_CATCH,LITERAL_FINALLY,LITERAL_IF,LITERAL_ELSE,LITERAL_DO"/>
        </module>
        <module name="RightCurly">
            <property name="option" value="alone_or_singleline"/>
            <property name="tokens"
                      value="CLASS_DEF,METHOD_DEF,CTOR_DEF,LITERAL_FOR,LITERAL_WHILE,STATIC_INIT,INSTANCE_INIT,RECORD_DEF"/>
        </module>
        <module name="EmptyBlock">
            <property name="option" value="text"/>
        </module>
        <module name="EmptyCatchBlock">
            <property name="exceptionVariableName" value="ignored"/>
        </module>
        <module name="EmptyStatement"/>
        <module name="AvoidNestedBlocks"/>

        <!-- 空白 -->
        <module name="WhitespaceAround">
            <property name="allowEmptyTypes" value="true"/>
            <property name="allowEmptyConstructors" value="true"/>
            <property name="allowEmptyMethods" value="true"/>
            <property name="allowEmptyLambdas" value="true"/>
            <property name="allowEmptyCatches" value="true"/>
        </module>
        <module name="WhitespaceAfter"/>
        <module name="NoWhitespaceBefore"/>
        <module name="NoWhitespaceAfter"/>
        <module name="GenericWhitespace"/>
        <module name="MethodParamPad"/>
        <module name="ParenPad"/>
        <module name="TypecastParenPad"/>
        <module name="SingleSpaceSeparator"/>
        <module name="NoLineWrap"/>
        <module name="EmptyForInitializerPad"/>
        <module name="EmptyForIteratorPad"/>

        <!-- 语句与声明 -->
        <module name="OneStatementPerLine"/>
        <module name="MultipleVariableDeclarations"/>
        <module name="OneTopLevelClass"/>
        <module name="OuterTypeFilename"/>

        <!-- 折行与缩进 -->
        <module name="Indentation">
            <property name="basicOffset" value="4"/>
            <property name="braceAdjustment" value="0"/>
            <property name="caseIndent" value="4"/>
            <property name="throwsIndent" value="8"/>
            <property name="arrayInitIndent" value="4"/>
            <property name="lineWrappingIndentation" value="4"/>
            <property name="forceStrictCondition" value="false"/>
        </module>
        <module name="OperatorWrap">
            <property name="option" value="nl"/>
        </module>
        <module name="SeparatorWrap">
            <property name="option" value="eol"/>
            <property name="tokens" value="COMMA,DOT"/>
        </module>

        <!-- 注解与修饰符 -->
        <module name="AnnotationLocation">
            <property name="allowSamelineSingleParameterlessAnnotation" value="false"/>
            <property name="allowSamelineParameterizedAnnotation" value="false"/>
            <property name="allowSamelineMultipleAnnotations" value="false"/>
        </module>
        <module name="ModifierOrder"/>
        <module name="ArrayTypeStyle"/>
        <module name="UpperEll"/>

        <!-- 命名规约：白名单仅 i/j/k/id/to -->
        <module name="PackageName">
            <property name="format" value="^[a-z]+(\.[a-z][a-z0-9]*)*$"/>
        </module>
        <module name="TypeName">
            <property name="format" value="^[A-Z][a-zA-Z0-9]*$"/>
        </module>
        <module name="MethodName">
            <property name="format" value="^[a-z][a-zA-Z0-9]*$"/>
        </module>
        <module name="MemberName">
            <property name="format" value="^(?:id|to|[a-z][a-zA-Z0-9]{2,})$"/>
        </module>
        <module name="StaticVariableName">
            <property name="format" value="^(?:id|to|[a-z][a-zA-Z0-9]{2,})$"/>
        </module>
        <module name="ParameterName">
            <property name="format" value="^(?:id|to|[a-z][a-zA-Z0-9]{2,})$"/>
        </module>
        <module name="RecordComponentName">
            <property name="format" value="^(?:id|to|[a-z][a-zA-Z0-9]{2,})$"/>
        </module>
        <module name="CatchParameterName">
            <property name="format" value="^(?:id|to|[a-z][a-zA-Z0-9]{2,})$"/>
        </module>
        <module name="PatternVariableName">
            <property name="format" value="^(?:id|to|[a-z][a-zA-Z0-9]{2,})$"/>
        </module>
        <module name="LocalVariableName">
            <property name="format" value="^(?:i|j|k|id|to|[a-z][a-zA-Z0-9]{2,})$"/>
        </module>
        <module name="LocalFinalVariableName">
            <property name="format" value="^(?:i|j|k|id|to|[a-z][a-zA-Z0-9]{2,})$"/>
        </module>
        <module name="LambdaParameterName">
            <property name="format" value="^(?:i|j|k|id|to|[a-z][a-zA-Z0-9]{2,})$"/>
        </module>
        <module name="ConstantName">
            <property name="format" value="^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$|^LOG$"/>
        </module>
    </module>
</module>
```

> 上述配置**已在本机实测通过**（checkstyle 10.21.4 + maven-checkstyle-plugin 3.6.0，用未整改的 `EngineConfig.java` 作输入）：配置可正常加载，报出 96 条违规，分布为 `WhitespaceAround` 55、`WhitespaceAfter` 28、`NeedBraces` 4、`LineLength` 3、`LeftCurly` 2、`AvoidStarImport` 1、`OneStatementPerLine` 1、`ParameterName` 1、`LocalVariableName` 1 —— 恰好覆盖 R1/R2/R4/R5/R6/R11。
>
> 实测中修掉的两个坑，**不要改回去**：
> - `OneTopLevelClassDeclaration` 不存在，正确模块名是 `OneTopLevelClass`；
> - `AnnotationLocation` 在 10.21.4 里**没有** `allowSamelineSingleAnnotations` 属性，正确的是 `allowSamelineSingleParameterlessAnnotation` / `allowSamelineParameterizedAnnotation` / `allowSamelineMultipleAnnotations` 三个。写错属性名会导致整个 `TreeWalker` 初始化失败，报错信息是 `Property 'xxx' does not exist`。
>
> 项目内无参数级注解（`(@Foo ...)` 形态为零），因此 `allowSamelineParameters` 保持默认即可。

- [ ] **Step 3: 在 root `pom.xml` 绑定插件**

在 `maven-surefire-plugin` 那段 `</plugin>` 之后、`</plugins>` 之前插入：

```xml
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-checkstyle-plugin</artifactId>
                <version>3.6.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.puppycrawl.tools</groupId>
                        <artifactId>checkstyle</artifactId>
                        <version>10.21.4</version>
                    </dependency>
                </dependencies>
                <configuration>
                    <configLocation>${maven.multiModuleProjectDirectory}/config/checkstyle/checkstyle.xml</configLocation>
                    <includeTestSourceDirectory>true</includeTestSourceDirectory>
                    <consoleOutput>true</consoleOutput>
                    <failOnViolation>true</failOnViolation>
                    <violationSeverity>warning</violationSeverity>
                    <linkXRef>false</linkXRef>
                </configuration>
                <executions>
                    <execution>
                        <id>checkstyle-check</id>
                        <phase>verify</phase>
                        <goals>
                            <goal>check</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
```

- [ ] **Step 4: 首次试跑，枚举剩余违规**

Run: `mvn -B verify 2>&1 | tee /tmp/cs-round1.txt | grep -E "\[WARN\].*\.java|BUILD" | tail -40`

Expected: 大概率 `BUILD FAILURE` 并列出剩余违规。这是**正常的**——本步目的是枚举，不是通过。

Run: `grep -cE "\[WARN\].*\.java" /tmp/cs-round1.txt`

Expected: 一个数字，记为剩余违规条数 N。若 N = 0 直接跳到 Step 6。

- [ ] **Step 5: 按违规类型逐个消解**

Run: `grep -oE "\[[A-Za-z]+\]$" /tmp/cs-round1.txt | sort | uniq -c | sort -rn | head -20`

Expected: 各规则的违规计数分布。按下表处理：

| 违规规则 | 处理 |
| --- | --- |
| `ImportOrder` | 按报错提示调整 import 顺序与空行。分组为 `io.github.mchgood` -> `org` -> `java` -> `javax`，静态置底 |
| `UnusedImports` | 删掉该 import |
| `Indentation` | 按报错的 `expected level` 调整缩进。若确认是对 fluent 链或折行条件的**误报**且无法通过纯排版消除，在 `config/checkstyle/checkstyle.xml` 的 `Indentation` module 上方加 XML 注释记录该误报形态，**不得**删除整个 module |
| `LineLength` | 按折行方向规则继续拆行 |
| `NeedBraces` / `LeftCurly` / `RightCurly` | 补大括号或调整括号位置 |
| `WhitespaceAround` / `WhitespaceAfter` / `NoWhitespaceBefore` / `NoWhitespaceAfter` / `GenericWhitespace` | 补或删空格 |
| `OneStatementPerLine` / `MultipleVariableDeclarations` / `OneTopLevelClass` | 拆行；`OneTopLevelClass` 违规说明一个文件里有多个顶级类型，需按手册拆文件——本项目实测无此情况 |
| `AnnotationLocation` | 注解移到独占一行 |
| `OperatorWrap` / `SeparatorWrap` | 按折行方向规则移动运算符或点号/逗号 |
| `LocalVariableName` / `ParameterName` / `MemberName` / `LambdaParameterName` / `CatchParameterName` / `PatternVariableName` / `RecordComponentName` | **改名**，按术语表或按运行时类型语义化；把新旧名作为一条映射补进 `/tmp/rename-map.json` 对应文件的条目（脚本据此擦除两侧），再重跑该文件的 token 等价校验。**禁止**放宽 `format` 正则或加白名单 |
| `ConstantName` | 常量改为全大写下划线分隔（`ALLOWED_NODE_KINDS` 已符合） |
| `EmptyBlock` | 空块内补一行说明注释 |
| `EmptyCatchBlock` | catch 形参改名为 `ignored`，或补一行说明注释 |
| `ModifierOrder` / `ArrayTypeStyle` / `UpperEll` / `OuterTypeFilename` / `EmptyStatement` / `AvoidNestedBlocks` | 按报错直接修正 |

每修一轮重跑：

```bash
mvn -B verify 2>&1 | tee /tmp/cs-roundN.txt | grep -E "\[WARN\].*\.java|BUILD" | tail -20
grep -cE "\[WARN\].*\.java" /tmp/cs-roundN.txt
```

Expected: 违规条数单调下降，直到 `BUILD SUCCESS` 且计数为 0。

- [ ] **Step 6: 确认闸门真的会拦**

故意引入一处违规，验证闸门生效，然后还原：

```bash
cp flow-engine-core/src/main/java/io/github/mchgood/flow/config/EngineConfig.java /tmp/EngineConfig.bak.java
python3 - <<'PY'
import pathlib
p = pathlib.Path('flow-engine-core/src/main/java/io/github/mchgood/flow/config/EngineConfig.java')
p.write_text(p.read_text(encoding='utf-8').replace(
    'if (workerThreads < 1', 'if(workerThreads<1', 1))
PY
mvn -B verify 2>&1 | grep -E "WhitespaceAround|NeedBraces|BUILD" | tail -5
echo "EXIT_ABOVE_SHOULD_BE_FAILURE"
cp /tmp/EngineConfig.bak.java flow-engine-core/src/main/java/io/github/mchgood/flow/config/EngineConfig.java
git diff --stat flow-engine-core/src/main/java/io/github/mchgood/flow/config/EngineConfig.java
```

Expected: 出现 `WhitespaceAround` 或类似违规且 `BUILD FAILURE`；还原后 `git diff --stat` **无输出**。

- [ ] **Step 7: 在 `AGENTS.md` 追加闸门说明**

在 `## Testing expectations` 段落的 `mvn verify` 代码块之后、`Add tests for every behavior change.` 之前插入：

```markdown
`mvn verify` also runs a Checkstyle gate (`config/checkstyle/checkstyle.xml`) that enforces the
Alibaba Java convention subset for formatting and naming: braces on every control-flow statement,
one statement and one variable declaration per line, 120-column limit, no wildcard imports,
ordered import groups, and no identifier shorter than three characters outside the whitelist
`i`/`j`/`k` (loop counters and lambda parameters), `id`, and `to`. Fix violations by changing the
code; do not relax a rule `format` or add a whitelist entry to make a failure pass.
```

- [ ] **Step 8: 全量验证**

Run: `mvn -B verify 2>&1 | grep -E "Tests run:.*Failures: [0-9]+, Errors|checkstyle|BUILD" | tail -12`

Expected: 四个模块 `Tests run: 98` / `84` / `22` / `1` 全部 `Failures: 0, Errors: 0`；出现 `checkstyle-check` execution 且无违规；末行 `BUILD SUCCESS`。

Run: `python3 scripts/check-coverage.py`

Expected:
```
LINE: <covered>/<total> = <pct>%; minimum 95%
BRANCH: <covered>/<total> = <pct>%; minimum 88%
```
两个百分比均 >= 门槛，退出码 0。与 `/tmp/baseline-coverage.txt` 对比：`LINE` 不得低于 **95%**、`BRANCH` 不得低于 **88%**。若跌破，按 Global Constraints 定位为缺陷并修复，**禁止改 `scripts/check-coverage.py` 的阈值**。

Run: `python3 /tmp/style-scan.py . | tail -3`

Expected: `files=50 long=0 multi=0 star=0`（`nobrace` 允许非零，见 Task 1 Step 2）。

- [ ] **Step 9: 全项目残留检查**

```bash
echo "--- wildcard imports ---"
grep -rn "import .*\*;" --include="*.java" . | grep -v "/target/" || echo "none"
echo "--- lines over 120 ---"
find . -name "*.java" -not -path "*/target/*" -exec awk 'length($0)>120 {print FILENAME":"FNR}' {} + || true
echo "--- short identifiers outside whitelist ---"
python3 /tmp/inventory-short.py 2>/dev/null || grep -rnE "\b(?:var|final var)\s+[a-z]{1,2}\s*=" --include="*.java" . | grep -v "/target/" | grep -vE "\b(var|final var)\s+(i|j|k|id|to)\s*=" || echo "none"
echo "--- tabs ---"
grep -rlP "\t" --include="*.java" . | grep -v "/target/" || echo "none"
```

Expected: 四段全部输出 `none`（或空）。若 `grep -P` 在 macOS 上不可用，用 `grep -rl "$(printf '\t')" --include="*.java" . | grep -v /target/ || echo none` 替代。

- [ ] **Step 10: 全量 token 等价校验（对照整改前的原始 commit）**

```bash
BASE=$(git log --oneline -20 | grep "feat: declare generic FlowNode output contracts" | awk '{print $1}')
echo "BASE=$BASE"
python3 /tmp/style-equiv.py "$BASE" $(git diff --name-only "$BASE" -- '*.java')
```

Expected: `BASE` 是 `a97db33`；输出含 2 条 `EQUIV EXEMPT`，其余文件除 R3 声明拆分点外 `failed=0`（拆分点按第 0 节协议第 2(a) 条处理并已入 `FOLDS`）。

> 若某文件 FAIL，说明存在真实的语义改动（改名差异已被词表擦除，不会触发 FAIL）。逐文件看 `first divergence` 上下文并修回原语义；若确认是 Task 10 Step 5 新引入且未登记的改名，把该条映射补进 `/tmp/rename-map.json` 后重跑。**不得**扩大 `EXEMPT` 集合。

- [ ] **Step 11: 提交**

```bash
git add config/checkstyle/checkstyle.xml pom.xml AGENTS.md
git status --short
git commit -m "build: enforce Alibaba style rules with checkstyle gate"
```

Expected: `git add` 后 `git status --short` 显示 `A  config/checkstyle/checkstyle.xml`、`M  AGENTS.md`、`M  pom.xml` 三条，无其他文件。若 Step 5 修违规时改动了 java 文件，一并 `git add` 进本 commit 并在 `git status --short` 里逐条核对。

---

## 验收清单

全部勾选才算完成：

- [ ] `mvn -B verify` BUILD SUCCESS，`checkstyle-check` execution 零违规
- [ ] 测试总数 **205**（98 + 84 + 22 + 1），`Failures: 0, Errors: 0, Skipped: 0`
- [ ] `python3 scripts/check-coverage.py` 通过：LINE >= 95%、BRANCH >= 88%
- [ ] `python3 /tmp/style-scan.py .` 末行 `files=50 long=0 multi=0 star=0`（`nobrace` 仅趋势）
- [ ] `python3 /tmp/style-equiv.py a97db33 <全部改动文件>` 输出 `failed=0`，`EXEMPT` 恰为 2 个文件
- [ ] 8 处超长 Mermaid 字面量折行后经脚本验证 `IDENTICAL`（Task 6 Step 9 计 3 处、Task 8 Step 4 计 5 处）
- [ ] 全项目 `grep` 无 `import .*\*;`
- [ ] 全项目单行长度 <= 120
- [ ] 术语表内所有旧名在 `src/` 下零残留（`WORKER`、`pool`、`permits`、`ALLOWED`、`.in`、`.out`、`loc` 等）
- [ ] 命名白名单外无 <=2 字符标识符
- [ ] 全项目 `@Override` 相比基线新增恰好 11（core 测试 +5，spring main +6）
- [ ] `@Override` 全部独占一行，无 `@Override public` 同行形态
- [ ] `git log --oneline` 显示 9 个新 commit（Task 2-10），每个独立可编译可测试
- [ ] `docs/requirements.md`、`docs/technical-design.md`、`docs/quick-start.md`、`docs/spring-boot.md`、`docs/testing-coverage.md`、`README.md`、`.github/workflows/ci.yml` 均**未被改动**（`git diff a97db33 --stat -- docs README.md .github` 只显示 `docs/superpowers/` 下的新增）
- [ ] `AGENTS.md` 只新增了 Step 7 那一段闸门说明

## 已知风险与回退

| 风险 | 应对 |
| --- | --- |
| `Indentation` 对 fluent 链或折行条件持续误报 | 保持 `forceStrictCondition=false`；仍误报时在该 module 上方加 XML 注释记录形态，不删除 module、不放宽其他规则 |
| 某处改名导致编译失败但报错信息不明确 | `mvn -pl <module> -am compile 2>&1 \| grep -E "symbol:\|location:"` 定位；改名必须整词替换，用 `\b` 边界正则，不要用裸文本替换 |
| token 等价校验大面积 FAIL | 改名不会触发 FAIL（词表已擦除），所以大面积 FAIL 通常意味着 `/tmp/rename-map.json` 缺条目、或改写真的动了结构。先补齐词表，再逐文件看 `first divergence` 上下文；**不得**通过扩大 `EXEMPT` 绕过 |
| 覆盖率跌破门槛 | 说明改写改变了代码路径，属缺陷。用 `flow-engine-coverage/target/site/jacoco-aggregate/index.html` 定位未覆盖行，对照 `git diff` 找出被意外删除或改条件的语句 |
| 需要整体回退 | 每个 commit 独立可编译可测试，`git revert <sha>` 逐个回退；或 `git reset --hard a97db33` 回到整改前基线（会丢弃全部整改成果，仅在确认方案不可行时使用） |

