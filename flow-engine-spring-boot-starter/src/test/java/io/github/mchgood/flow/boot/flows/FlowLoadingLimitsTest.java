package io.github.mchgood.flow.boot.flows;

import io.github.mchgood.flow.exception.FlowException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证加载预算、UTF-8 字节边界、重叠位置、损坏编码和切分放大限制。
 */
class FlowLoadingLimitsTest {
    @ParameterizedTest
    @CsvSource({"0,100,1,1", "1048577,1048577,1,1", "5,4,1,1", "5,268435457,1,1",
        "5,10,0,1", "5,10,1025,1", "5,10,1,0", "5,10,1,4097"})
    void invalidBudgetsFailBeforeReading(int document, int total, int count, int flows) {
        assertThrows(IllegalArgumentException.class, () -> new FlowLoadingLimits(document, total, count, flows));
    }

    @Test
    void utf8LimitIsBytesAndExactBoundaryIsAccepted(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("flow.md");
        FlowLoadingLimits limits = new FlowLoadingLimits(5, 10, 2, 2);
        LocalMarkdownFlowSource source = new LocalMarkdownFlowSource(List.of(file.toUri().toString()), limits);
        Files.writeString(file, "abcde");
        assertEquals("abcde", source.load().get(0).markdown());
        Files.writeString(file, "中文");
        assertEquals("FLOW_LOADING_LIMIT", assertThrows(FlowException.class, source::load).code());
    }

    @Test
    void aggregateBudgetAndDocumentCountApplyBeforeRegistration(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("a.md"), "12345");
        Files.writeString(directory.resolve("b.md"), "12345");
        List<String> locations = List.of(directory.toUri() + "*.md");
        var byteLimited = new LocalMarkdownFlowSource(locations, new FlowLoadingLimits(5, 9, 2, 2));
        var countLimited = new LocalMarkdownFlowSource(locations, new FlowLoadingLimits(5, 10, 1, 2));
        assertEquals("FLOW_LOADING_LIMIT", assertThrows(FlowException.class, byteLimited::load).code());
        assertEquals("FLOW_LOADING_LIMIT", assertThrows(FlowException.class, countLimited::load).code());
    }

    @Test
    void overlappingLocationsReadResourceOnlyOnce(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("flow.md");
        Files.writeString(file, "hello");
        var source = new LocalMarkdownFlowSource(List.of(file.toUri().toString(), directory.toUri() + "*.md"),
            new FlowLoadingLimits(5, 5, 1, 1));
        assertEquals(1, source.load().size());
    }

    @Test
    void damagedUtf8AndNonLocalLocationsFail(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("flow.md");
        Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThrows(UncheckedIOException.class,
            () -> new LocalMarkdownFlowSource(List.of(file.toUri().toString())).load());
        assertThrows(IllegalArgumentException.class,
            () -> new LocalMarkdownFlowSource(List.of("https://example.invalid/flow.md")).load());
        assertThrows(IllegalArgumentException.class, () -> new LocalMarkdownFlowSource(List.of(" ")).load());
        assertThrows(FlowException.class,
            () -> new LocalMarkdownFlowSource(Collections.nCopies(33, "flow.md")));
    }

    @Test
    void parserRejectsOversizedTextAndTooManyFlows() {
        MarkdownFlowParser parser = new MarkdownFlowParser();
        assertEquals("FLOW_LOADING_LIMIT", assertThrows(FlowException.class,
            () -> parser.split("memory", "中中", new FlowLoadingLimits(5, 10, 1, 1))).code());
        String flow = "\n```mermaid\nflowchart TD\nstart([s]) --> finish([f])\n```\n";
        assertEquals("FLOW_LOADING_LIMIT", assertThrows(FlowException.class,
            () -> parser.split("memory", "# first" + flow + "# second" + flow,
                new FlowLoadingLimits(1024, 1024, 1, 1))).code());
    }

    @Test
    void parserBoundsLineNumberPaddingAmplification() {
        String flow = "\n```mermaid\nflowchart TD\nstart([s]) --> finish([f])\n```\n";
        String text = "\n".repeat(800) + "# first" + flow + "# second" + flow + "# third" + flow;
        assertEquals("FLOW_LOADING_LIMIT", assertThrows(FlowException.class,
            () -> new MarkdownFlowParser().split("memory", text,
                new FlowLoadingLimits(1024, 1200, 1, 3))).code());
    }
}
