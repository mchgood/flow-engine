# 生产接入与发布

适用版本：0.1.0；验证基线为 Java 17、Spring 7.0.9、Boot 4.1.1。只承诺本基线经过验证，其他版本需要单独完成接入测试。框架运行于单进程，不提供持久化恢复、全局事务、分布式调度或流程平台。

## 构建与交付

```bash
mvn -Pproduction-verification,release-artifacts clean install
python3 scripts/check-coverage.py
mvn -f verification/consumer/pom.xml test
```

第一步包括功能测试、固定种子文本变异、持续并发/宽图测试、Checkstyle 和 Javadoc，并生成二进制、sources、javadoc JAR。独立 consumer 不继承项目 parent，只消费 install 后的 Starter，验证真实打包产物的自动配置发现、流程文件加载和业务执行。覆盖模块只用于构建；消费者只引入 starter（Boot）或 spring（普通 Spring），纯 Java 可使用 core 与自己的 SPI 适配。

`production-verification` 默认运行 16 个调用者、每人 2000 次父流程调用。较大验证可使用 `-Dflow.soak.callers=32 -Dflow.soak.iterations=10000`；调用者范围 1–32，迭代范围 1–10000。测试结果在各模块 `target/surefire-reports`；观察延迟在 core 的 `target/soak-report.txt`。这些数据包含 JaCoCo 和测试开销，不是业务吞吐保证或 JMH 基准。

构建设置固定归档时间戳。正式交付时在同一 JDK/依赖环境做两次干净构建并比较 JAR SHA-256；同一源码及构建设置应产生相同产物。不同 JDK 的 Javadoc 输出不保证逐字节一致。构建方式参考 [Maven 可复现构建指南](https://maven.apache.org/guides/mini/guide-reproducible-builds.html)；sources 使用 [jar-no-fork](https://maven.apache.org/plugins-archives/maven-source-plugin-3.3.1/jar-no-fork-mojo.html)。

仓库准备 0.1.0 交付，但源码提交不等于制品已发布 Maven Central。可先 install，或由宿主团队在有权限的内部 Maven 仓库发布；仓库地址、凭据和公开发布另行决定。0.1.x 补丁应保持已公开接口和流程语义兼容；需破坏性变更时升级次版本并附迁移说明。internal 包不属于兼容承诺。

## 流程加载预算

```yaml
flow-engine:
  flows:
    enabled: true
    locations: ["classpath*:flows/*.md"]
    max-document-bytes: 1048576
    max-total-bytes: 16777216
    max-documents: 128
    max-flows: 256
```

- 单份原文最多 1 MiB UTF-8；内置来源读取至预算加一字节即拒绝，不依赖文件声明大小。损坏 UTF-8 失败，不静默替换字符。
- 原文累计字节和按原行号展开后的文本累计字节分别不得超过 max-total-bytes。跨自定义来源也计入预算；任何超限以 FLOW_LOADING_LIMIT 启动失败，整批不注册。
- 批次文档数和流程数有界；默认值如上。document 范围 1–1048576 字节，total 不小于 document 且至多 268435456 字节，文档 1–1024，流程 1–4096。
- 内置来源最多 32 个位置，仅接受本地文件/classpath 位置，按资源 URL 去重并排序。零匹配模式是正常情况；若业务依赖某流程，宿主应在 readiness 前确认所需流程已加载。
- 自定义 FlowSource 必须自行限制网络读取、超时和返回列表分配；注册器只能在 load 返回后核对内容。Spring 资源解析器自身的目录遍历/匹配开销不由字节预算消除，避免扫描整个文件系统。
- 一份 MD 的一级标题即 flowId，每段只含一个 Mermaid 图；核心 register API 仍一次注册单流程 Markdown。关闭 flows.enabled 会停止消费全部 FlowSource。

## 执行与副作用

FlowEngine 为共享实例。Bean 使用 singleton 且必须线程安全，输入/结果业务对象只共享引用，不被深拷贝。别名、子流程隔离的是引擎状态，不是可变业务对象。SpEL 只读语法不意味着业务 getter 没有副作用，也不是不可信代码沙箱；流程定义必须来自可信宿主。

设置工作线程、队列、根准入和单树在途额度，按下游连接池与外部服务容量选择；不要仅按 CPU 增大线程数。FLOW_REJECTED/RESOURCE_REJECTED 应由宿主映射为受控的忙碌/拒绝结果，避免无限重试。节点失败在 FlowResult 内体现，不能仅用“不抛异常”判定成功。

节点超时、父超时、关闭或其他分支失败时，前置钩子返回后会再次检查是否允许启动业务；拒绝启动时仍发出节点终态通知。业务真正开始后，取消始终是协作式的：设置 HTTP/数据库客户端超时并响应中断。框架不回滚外部副作用；重试节点必须具备业务幂等性，Spring 事务不跨工作线程自动形成全流程事务。

流程级钩子在调用线程，节点级钩子在 worker；钩子应快速返回。节点后置钩子在业务物理返回后通知，可能晚于流程强制超时的返回；忽略中断且永不退出的任务也无法保证通知。不要把此钩子作为超时实时告警的唯一来源。执行上下文、MDC、安全主体和事务不会自动从调用线程传播。

同步 execute 占用调用线程；独立子流程不占工作线程等待。调用方中断可请求停止执行树；不提供独立的持久化任务句柄或 resume API。

## 上线与运行

启动时先完成自动注册再接收业务；任何定义、Bean、引用或资源超限错误使启动失败。运行日志可通过 io.github.mchgood.flow.runtime 的 DEBUG 事件定位 executionId/flowId/nodeId/reason；默认不输出输入/结果。通过拦截器接入应用现有 metrics/tracing，避免记录隐私、业务原文和无限基数标签。

监控根拒绝/任务拒绝、失败和超时率、调用延迟、工作线程、宿主下游连接池与堆使用。返回结果的 physicalExitUnconfirmed 非空意味着取消后的物理退出尚未确认，持续出现应排查下游超时与中断处理。

关闭前由宿主停止接收新请求，再关闭容器。close-timeout 内等待根调用收尾，随后终结剩余执行并中断 worker；不能保证顽固业务线程已退出。平台最终退出期限应留足应用和下游收尾时间。仅接受同进程生命周期，重启丢失在途状态属于本框架既定边界。

首批在真实下游、代表性图规模和峰值并发下做灰度，观察拒绝、错误和资源曲线；需要容量承诺时单独做基准和更长时间负载验证。当前测试不能证明所有并发交错、所有 Mermaid 文本或所有宿主版本均正确。
