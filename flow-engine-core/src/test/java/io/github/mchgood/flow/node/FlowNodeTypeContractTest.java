package io.github.mchgood.flow.node;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import javax.tools.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 通过 Java 编译器验证节点输出泛型确实约束实现，避免接口退化为 Object。 */
class FlowNodeTypeContractTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"123,false", "Integer.toString(123),true"})
    void declaredOutputTypeIsEnforcedAtCompileTime(String expression, boolean expected) throws Exception {
        var compiler=ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler,"Tests require a JDK");
        Path source=directory.resolve("TypedNode.java");
        Files.writeString(source,"""
            import io.github.mchgood.flow.node.FlowNode;
            import io.github.mchgood.flow.node.NodeContext;
            class TypedNode implements FlowNode<String> {
                public String execute(NodeContext context) { return %s; }
                String call(FlowNode<String> node, NodeContext context) throws Exception {
                    return node.execute(context);
                }
                FlowNode<String> lambda = context -> %s;
            }
            """.formatted(expression,expression));
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,null,null)) {
            String classes=Path.of(FlowNode.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            boolean succeeded=compiler.getTask(null,manager,diagnostics,
                List.of("-classpath",classes,"-d",directory.toString(),"-proc:none","-Xlint:rawtypes,unchecked","-Werror"),null,
                manager.getJavaFileObjects(source.toFile())).call();
            assertEquals(expected,succeeded,diagnostics.getDiagnostics().toString());
            if(!expected)assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d->d.getKind()==Diagnostic.Kind.ERROR));
        }
    }
}
