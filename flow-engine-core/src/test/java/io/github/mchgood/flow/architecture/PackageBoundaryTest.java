package io.github.mchgood.flow.architecture;

import io.github.mchgood.flow.internal.compiler.FlowCompiler;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.spi.CompiledCondition;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.SourceLocation;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证依赖方向和编译图不可修改，防止包职责边界退化。
 */
class PackageBoundaryTest {
    @Test
    void dependenciesFollowPackageBoundaries() throws Exception {
        Path base = Path.of("src/main/java/io/github/mchgood/flow");
        Set<String> contracts = Set.of("api", "node", "spi", "config", "result", "exception");
        Pattern imports = Pattern.compile("import\\s+(?:static\\s+)?io\\.github\\.mchgood\\.flow\\.([\\w.]+)");
        try (var files = Files.walk(base)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = base.relativize(file).toString().replace('\\', '/');
                var matcher = imports.matcher(Files.readString(file));
                while (matcher.find()) {
                    String dependency = matcher.group(1);
                    if (contracts.contains(relative.split("/")[0])) {
                        assertFalse(dependency.startsWith("internal.") || dependency.startsWith("runtime.")
                            || dependency.startsWith("spring."), file + " imports " + dependency);
                    }
                    if (relative.startsWith("internal/graph/")) {
                        assertFalse(dependency.startsWith("internal.compiler.") || dependency.startsWith("runtime."),
                            file + " imports " + dependency);
                    }
                }
                assertFalse(Files.readString(file).contains("import org.springframework."), file.toString());
            }
        }
    }

    @Test
    void compilerPublishesReadOnlyTopologyWithConsistentEdges() {
        ConditionEvaluator unused = new ConditionEvaluator() {
            @Override
            public CompiledCondition parse(String text, SourceLocation location) { throw new AssertionError(); }
            @Override
            public boolean evaluate(CompiledCondition condition, NodeContext context) { throw new AssertionError(); }
        };
        var graph = new FlowCompiler(id -> context -> null, unused).compile("sample", """
            ```mermaid
            flowchart TD
                start([开始]) --> work["任务"]
                work --> finish([结束])
            ```
            """);
        var task = graph.nodes.get("work");
        assertSame(task.incomingEdges.get(0), graph.nodes.get("start").outgoingEdges.get(0));
        assertSame(task, task.incomingEdges.get(0).to);
        assertThrows(UnsupportedOperationException.class, () -> graph.nodes.clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.ordered.clear());
        assertThrows(UnsupportedOperationException.class, () -> task.incomingEdges.clear());
        assertThrows(UnsupportedOperationException.class, () -> task.outgoingEdges.clear());
        assertThrows(UnsupportedOperationException.class, () -> task.ancestors.clear());
    }
}
