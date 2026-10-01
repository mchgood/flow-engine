package io.github.mchgood.flow.boot.flows;

import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.internal.compiler.FlowCompiler;
import io.github.mchgood.flow.spring.SpelConditionEvaluator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证一级标题切分、围栏与深层标题识别、行号保持以及各类非法文档的报错定位。
 */
class MarkdownFlowParserTest {
    private final MarkdownFlowParser parser = new MarkdownFlowParser();

    @Test
    void splitsTwoHeadingsInFileOrderAndKeepsFileLines() {
        String markdown = String.join("\n",
            "intro text",
            "# alpha",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```",
            "# beta",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```");
        Map<String, String> sections = parser.split("file.md", markdown);
        assertThat(sections.keySet()).containsExactly("alpha", "beta");
        assertThat(sections.get("alpha").split("\n", -1)).hasSize(6);
        assertThat(sections.get("alpha").split("\n", -1)[0]).isEmpty();
        assertThat(sections.get("alpha").split("\n", -1)[1]).isEqualTo("# alpha");
        assertThat(sections.get("beta").split("\n", -1)).hasSize(11);
        assertThat(sections.get("beta").split("\n", -1)[6]).isEqualTo("# beta");
    }

    @Test
    void sectionsKeepFileLineIndexesViaBlankPadding() {
        String markdown = String.join("\n",
            "intro text",
            "# alpha",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```",
            "# beta",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```");
        Map<String, String> sections = parser.split("file.md", markdown);
        String[] fileLines = markdown.split("\n", -1);
        int[][] ranges = {{1, 6}, {6, fileLines.length}};
        for (int k = 0; k < sections.size(); k++) {
            String[] sectionLines = sections.get(k == 0 ? "alpha" : "beta").split("\n", -1);
            for (int i = ranges[k][0]; i < ranges[k][1]; i++) {
                assertThat(sectionLines[i]).isEqualTo(fileLines[i]);
            }
        }
    }

    @Test
    void compilerErrorsKeepFileAbsoluteLinesInSection() {
        String markdown = String.join("\n",
            "# alpha",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```",
            "# beta",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> miss[\"x\"]",
            "    miss --> finish([f])",
            "```");
        Map<String, String> sections = parser.split("file.md", markdown);
        FlowCompiler compiler = new FlowCompiler(beanId -> null, new SpelConditionEvaluator());
        FlowException failure = assertThrows(FlowException.class,
            () -> compiler.compile("beta", sections.get("beta")));
        assertThat(failure.code()).isEqualTo("BEAN_NOT_FOUND");
        assertThat(failure.getMessage()).contains("beta:9").doesNotContain("beta:3");
    }

    @Test
    void headingInsideFenceDoesNotSplit() {
        String markdown = String.join("\n",
            "# alpha",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```",
            "```text",
            "# inside",
            "```");
        assertThat(parser.split("file.md", markdown).keySet()).containsExactly("alpha");
    }

    @Test
    void deeperHeadingsDoNotSplit() {
        String markdown = String.join("\n",
            "# alpha",
            "## sub heading",
            "### deeper",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```");
        assertThat(parser.split("file.md", markdown).keySet()).containsExactly("alpha");
    }

    @Test
    void trailingHashesAreStripped() {
        String markdown = String.join("\n",
            "# alpha #",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```");
        assertThat(parser.split("file.md", markdown).keySet()).containsExactly("alpha");
    }

    @Test
    void invalidHeadingFailsWithSourceAndLine() {
        String markdown = String.join("\n",
            "intro",
            "# Bad-Name",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```");
        FlowException failure = assertThrows(FlowException.class, () -> parser.split("file.md", markdown));
        assertThat(failure.code()).isEqualTo("INVALID_FLOW_HEADING");
        assertThat(failure.getMessage()).contains("file.md:2").contains("Bad-Name");
    }

    @Test
    void duplicateHeadingFailsWithLine() {
        String markdown = String.join("\n",
            "# alpha",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```",
            "# alpha",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```");
        FlowException failure = assertThrows(FlowException.class, () -> parser.split("file.md", markdown));
        assertThat(failure.code()).isEqualTo("INVALID_FLOW_HEADING");
        assertThat(failure.getMessage()).contains("Duplicate").contains("file.md:6");
    }

    @Test
    void mermaidBeforeFirstHeadingFails() {
        String markdown = String.join("\n",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```",
            "# alpha",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```");
        FlowException failure = assertThrows(FlowException.class, () -> parser.split("file.md", markdown));
        assertThat(failure.code()).isEqualTo("MERMAID_BLOCK_COUNT");
        assertThat(failure.getMessage()).contains("file.md:1");
    }

    @Test
    void mermaidWithoutAnyHeadingFails() {
        String markdown = String.join("\n",
            "```mermaid",
            "flowchart TD",
            "    start([s]) --> finish([f])",
            "```");
        FlowException failure = assertThrows(FlowException.class, () -> parser.split("file.md", markdown));
        assertThat(failure.code()).isEqualTo("MERMAID_BLOCK_COUNT");
        assertThat(failure.getMessage()).contains("file.md:1");
    }

    @Test
    void sectionWithoutMermaidFails() {
        String markdown = "# alpha\njust text, no diagram\n";
        FlowException failure = assertThrows(FlowException.class, () -> parser.split("file.md", markdown));
        assertThat(failure.code()).isEqualTo("MERMAID_BLOCK_COUNT");
        assertThat(failure.getMessage()).contains("alpha").contains("file.md:1");
    }

    @Test
    void plainNotesAreSkipped() {
        assertThat(parser.split("file.md", "just notes\nmore notes\n")).isEmpty();
    }

    @Test
    void crlfAndBomAreHandled() {
        String markdown = "﻿# alpha\r\n```mermaid\r\nflowchart TD\r\n    start([s]) --> finish([f])\r\n```";
        Map<String, String> sections = parser.split("file.md", markdown);
        assertThat(sections.keySet()).containsExactly("alpha");
        assertThat(sections.get("alpha").split("\n", -1)).hasSize(5);
    }
}
