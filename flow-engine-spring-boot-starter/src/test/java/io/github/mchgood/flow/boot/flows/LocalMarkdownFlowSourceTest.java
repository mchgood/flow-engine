package io.github.mchgood.flow.boot.flows;

import io.github.mchgood.flow.spi.FlowDocument;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证本地来源的 classpath 与文件系统解析、确定性排序、忽略规则及 fail-fast 读取。
 */
class LocalMarkdownFlowSourceTest {

    @Test
    void loadsClasspathMarkdownSortedAndSkipsOtherFiles() {
        LocalMarkdownFlowSource source = new LocalMarkdownFlowSource(List.of("classpath*:it/flows/*.md"));
        List<FlowDocument> documents = source.load();
        assertThat(documents).hasSize(2);
        assertThat(documents).extracting(FlowDocument::sourceName).isSorted();
        assertThat(documents.get(0).markdown()).contains("# childFlow");
        assertThat(documents.get(1).markdown()).contains("# orderFlow");
    }

    @Test
    void zeroMatchesAndEmptyLocationsAreSilent() {
        assertThat(new LocalMarkdownFlowSource(List.of("classpath*:it/absent/*.md")).load()).isEmpty();
        assertThat(new LocalMarkdownFlowSource(List.of()).load()).isEmpty();
    }

    @Test
    void readsFileSystemDirectoryIgnoringNonMarkdown(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("b.md"), "# bFlow\n");
        Files.writeString(dir.resolve("a.md"), "# aFlow\n");
        Files.writeString(dir.resolve("ignore.txt"), "skip");
        LocalMarkdownFlowSource source = new LocalMarkdownFlowSource(List.of("file:" + dir + "/*.md"));
        List<FlowDocument> documents = source.load();
        assertThat(documents).extracting(FlowDocument::markdown).containsExactly("# aFlow\n", "# bFlow\n");
    }

    @Test
    void missingConcreteFileFailsFast() {
        LocalMarkdownFlowSource source = new LocalMarkdownFlowSource(List.of("file:/definitely/absent/flow.md"));
        assertThatThrownBy(source::load).isInstanceOf(UncheckedIOException.class).
            hasMessageContaining("file:/definitely/absent/flow.md");
    }

    @Test
    void resolveFailureFailsFast() {
        LocalMarkdownFlowSource source = new LocalMarkdownFlowSource(List.of("bad"), failingResolver());
        assertThatThrownBy(source::load).isInstanceOf(UncheckedIOException.class).
            hasMessageContaining("bad").hasCauseInstanceOf(IOException.class);
    }

    @Test
    void unreadableUrlFailsFast() {
        Resource resource = new ByteArrayResource("x".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "broken.md";
            }
        };
        LocalMarkdownFlowSource source = new LocalMarkdownFlowSource(List.of("mock"), fixedResolver(resource));
        assertThatThrownBy(source::load).isInstanceOf(UncheckedIOException.class).
            hasMessageContaining("Failed to resolve flow resource URL");
    }

    private ResourcePatternResolver failingResolver() {
        return new ResourcePatternResolver() {
            @Override
            public Resource[] getResources(String location) throws IOException {
                throw new IOException("boom");
            }

            @Override
            public Resource getResource(String location) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
    }

    private ResourcePatternResolver fixedResolver(Resource resource) {
        return new ResourcePatternResolver() {
            @Override
            public Resource[] getResources(String location) {
                return new Resource[] {resource};
            }

            @Override
            public Resource getResource(String location) {
                return resource;
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
    }
}
