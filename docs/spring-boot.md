# Spring Boot 接入

已验证的组合：Java 17+、Spring Boot 4.1.1、Spring Framework 7.0.9。Boot 3 不在本版支持范围。

## 1. 安装与依赖

当前尚未发布 Maven Central，先在仓库根目录执行 `mvn install`。宿主应用使用 Spring Boot 的 parent 或 BOM 管理 Boot 依赖版本，然后添加：

```xml
<dependency>
    <groupId>io.github.mchgood</groupId>
    <artifactId>flow-engine-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Starter 传递引入 Spring 适配、core 和基础 Boot Starter，不引入 Web 服务器。自动配置通过 `AutoConfiguration.imports` 发现；无需 `@EnableFlowEngine`、额外扫描框架包或手动创建引擎。

## 2. 最小可运行应用

将下面代码保存为宿主项目中的 `src/main/java/example/DemoApplication.java`。这是一个在启动后注册并执行流程的示例；实际业务可把注册放在初始化阶段，把执行放在业务 Service 中。

```java
package example;

import io.github.mchgood.flow.api.FlowEngine;
import io.github.mchgood.flow.node.FlowNode;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.util.Map;

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    FlowNode<String> greet() {
        return context -> "Hello, " + context.input(Map.class).get("name");
    }

    @Bean
    ApplicationRunner runFlow(FlowEngine engine) {
        return args -> {
            engine.register("helloFlow", """
                ```mermaid
                flowchart TD
                    start([开始]) --> greet["问候"]
                    greet --> finish([结束])
                ```
                """);
            var result = engine.execute("helloFlow", Map.of("name", "World"));
            if (!result.succeeded()) {
                throw new IllegalStateException(result.errors().toString());
            }
            System.out.println(result.results().get("greet").value());
        };
    }
}
```

`FlowEngine` 可直接构造器注入。自动配置只创建基础设施，不扫描流程文件、不自动注册或触发流程。文件路径、加载时机、flowId 均由应用管理；有子流程引用时，用 `registerAll` 一次注册完整定义集。容器关闭时自动调用引擎 `close()`，无需在每次执行后关闭共享引擎。

## 3. application.yml

所有参数都有默认值，最小应用可以不写任何配置：

```yaml
flow-engine:
  enabled: true
  worker-threads: 8
  queue-capacity: 128
  max-concurrent-executions: 64
  max-in-flight-per-execution: 8
  max-subflow-depth: 8
  max-executions-per-root: 128
  max-active-children: 32
  node-timeout: 30s
  gateway-timeout: 1s
  flow-timeout: 60s
  close-timeout: 10s
```

| 配置项（前缀 `flow-engine.`） | 含义与约束 |
| --- | --- |
| `enabled` | 默认 true；false 关闭本 Starter 的全部自动装配，用户自己声明的 Bean 不受影响 |
| `worker-threads` | 工作线程数，至少 1 |
| `queue-capacity` | 工作队列容量，至少 1 |
| `max-concurrent-executions` | 同时接纳的根流程数，至少 1 |
| `max-in-flight-per-execution` | 单执行实例在途任务数，至少 1 |
| `max-subflow-depth` | 子流程最大嵌套深度，0–32 |
| `max-executions-per-root` | 每个根流程累计执行实例上限，至少 1 |
| `max-active-children` | 活跃子执行上限，至少 1 |
| `node-timeout` | 业务节点期限 |
| `gateway-timeout` | 条件网关期限 |
| `flow-timeout` | 流程默认期限，可用 ExecutionOptions 对单次执行覆盖 |
| `close-timeout` | 引擎关闭等待期限 |

期限必须大于 0、最多 1 天，推荐明确写单位，例如 `250ms`、`30s`；ISO-8601 Duration 也可使用。自动配置创建 EngineConfig 时复用核心校验，非法容量或期限使启动失败；未知配置键和格式错误也会失败，避免拼写错误静默生效。配置绑定只在启动时进行，不支持运行中热更新。Starter 内含 IDE 配置元数据。

## 4. 覆盖默认实现

宿主定义下列类型的 Bean 时，相应默认 Bean 自动退让：

| Bean 类型 | 用途 |
| --- | --- |
| `FlowEngine` | 自定义整个引擎 |
| `NodeResolver` | 自定义业务节点解析 |
| `ConditionEvaluator` | 自定义条件求值 |
| `EngineConfig` | 程序化配置，优先于配置文件中对应资源参数 |

每种类型通常只定义一个 Bean；存在多个候选时需使用 `@Primary` 明确选择。替换条件求值器时，宿主需自行维持只读和严格 Boolean 语义。

不使用 Spring Boot 的项目继续依赖 `flow-engine-spring`，按照[普通 Spring 快速使用](quick-start.md)手动创建引擎即可。

自动配置设计参考 [Spring Boot 官方指南](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)，版本基线参考 [系统要求](https://docs.spring.io/spring-boot/system-requirements.html)。
