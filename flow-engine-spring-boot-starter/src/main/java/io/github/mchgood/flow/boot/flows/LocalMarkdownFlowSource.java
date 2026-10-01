package io.github.mchgood.flow.boot.flows;

import io.github.mchgood.flow.spi.FlowDocument;
import io.github.mchgood.flow.spi.FlowSource;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 从 classpath 或文件系统位置读取 Markdown 流程文档的内置来源。
 * <p>每个位置为 Ant 模式或具体文件路径，支持 classpath:、classpath*:、file: 与绝对路径，
 * 经 PathMatchingResourcePatternResolver 解析。模式零匹配属正常情况，静默返回空列表；
 * 具体路径的资源必须可读，打开或读取失败抛 UncheckedIOException，由自动加载转为启动失败。
 * 匹配按资源 URL 排序保证确定性；文件名不以 .md 结尾的资源忽略。本类不可变、线程安全，
 * 每次 load() 重新扫描。
 */
public final class LocalMarkdownFlowSource implements FlowSource {
    private final List<String> locations;
    private final ResourcePatternResolver resolver;

    /**
     * 使用默认解析器创建本地来源。
     *
     * @param locations Ant 模式或具体文件路径列表，可为空列表
     */
    public LocalMarkdownFlowSource(List<String> locations) {
        this(locations, new PathMatchingResourcePatternResolver());
    }

    /**
     * 使用指定解析器创建本地来源，便于测试注入。
     *
     * @param locations Ant 模式或具体文件路径列表
     * @param resolver 资源模式解析器
     */
    LocalMarkdownFlowSource(List<String> locations, ResourcePatternResolver resolver) {
        this.locations = List.copyOf(locations);
        this.resolver = resolver;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public List<FlowDocument> load() {
        List<Resource> matched = new ArrayList<>();
        for (String location : locations) {
            try {
                matched.addAll(Arrays.asList(resolver.getResources(location)));
            } catch (IOException exception) {
                throw new UncheckedIOException("Failed to resolve flow location " + location, exception);
            }
        }
        matched.sort(Comparator.comparing(this::urlOf));
        List<FlowDocument> documents = new ArrayList<>();
        for (Resource resource : matched) {
            String filename = resource.getFilename();
            if (filename == null || !filename.endsWith(".md")) {
                continue;
            }
            documents.add(new FlowDocument(urlOf(resource), read(resource)));
        }
        return List.copyOf(documents);
    }

    private String urlOf(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to resolve flow resource URL", exception);
        }
    }

    private String read(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read flow resource " + urlOf(resource), exception);
        }
    }
}
