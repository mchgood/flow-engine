package io.github.mchgood.flow.architecture;

import io.github.mchgood.flow.internal.compiler.FlowCompiler;
import io.github.mchgood.flow.node.NodeContext;
import io.github.mchgood.flow.spi.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.Set;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class PackageBoundaryTest {
    @Test
    void dependenciesFollowPackageBoundaries() throws Exception {
        Path base = Path.of("src/main/java/io/github/mchgood/flow");
        Set<String> contracts = Set.of("api", "node", "spi", "config", "result", "exception");
        Pattern imports = Pattern.compile("import\\s+(?:static\\s+)?io\\.github\\.mchgood\\.flow\\.([\\w.]+)");
        try (var files = Files.walk(base)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
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
            public CompiledCondition parse(String text, SourceLocation location) { throw new AssertionError(); }
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
        assertSame(task.in.get(0), graph.nodes.get("start").out.get(0));
        assertSame(task, task.in.get(0).to);
        assertThrows(UnsupportedOperationException.class, () -> graph.nodes.clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.ordered.clear());
        assertThrows(UnsupportedOperationException.class, () -> task.in.clear());
        assertThrows(UnsupportedOperationException.class, () -> task.out.clear());
        assertThrows(UnsupportedOperationException.class, () -> task.ancestors.clear());
    }
}
