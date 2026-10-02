package io.github.mchgood.flow.compiler;

import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.internal.compiler.FlowCompiler;
import io.github.mchgood.flow.spi.CompiledCondition;
import io.github.mchgood.flow.spi.ConditionEvaluator;
import io.github.mchgood.flow.spi.SourceLocation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定种子文本变异检查：畸形定义只能成为可执行图或带错误码的定义错误，不能泄漏解析器内部异常。
 * <p>这是有界变异测试，不替代覆盖率引导的 Fuzz 或语法证明。
 */
@Tag("soak")
@Timeout(30)
class CompilerMutationTest {
    @Test
    void mutatedTextHasOnlyDocumentedCompilerOutcomes() {
        ConditionEvaluator conditions = new ConditionEvaluator() {
            @Override
            public CompiledCondition parse(String text, SourceLocation location) {
                throw new FlowException("EXPRESSION_SYNTAX_ERROR", location.toString());
            }

            @Override
            public boolean evaluate(CompiledCondition expression, io.github.mchgood.flow.node.NodeContext context) {
                return false;
            }
        };
        FlowCompiler compiler = new FlowCompiler(id -> context -> null, conditions);
        String original = "```mermaid\nflowchart TD\nstart([s]) --> work[\"task\"] --> finish([f])\n```";
        String alphabet = "[]{}()_#|;\\\"<>-`~\n abc123中文";
        Random random = new Random(20261002L);
        int rejected = 0;
        for (int i = 0; i < 2000; i++) {
            StringBuilder text = new StringBuilder(original);
            for (int j = 0; j < 1 + random.nextInt(12); j++) {
                int position = random.nextInt(text.length());
                switch (random.nextInt(3)) {
                    case 0 -> text.deleteCharAt(position);
                    case 1 -> text.insert(position, alphabet.charAt(random.nextInt(alphabet.length())));
                    default -> text.setCharAt(position, alphabet.charAt(random.nextInt(alphabet.length())));
                }
            }
            try {
                assertNotNull(compiler.compile("mutated", text.toString()));
            } catch (FlowException exception) {
                rejected++;
                assertTrue(exception.code() != null && !exception.code().isBlank());
            }
        }
        assertTrue(rejected > 1000, "Mutation corpus did not exercise enough rejected inputs");
    }
}
