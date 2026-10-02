package io.github.mchgood.flow.boot.flows;

import io.github.mchgood.flow.spi.FlowDocument;
import io.github.mchgood.flow.spi.FlowSource;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 从 classpath 或文件系统位置读取 Markdown 流程文档的内置来源。
 * <p>每个位置为 Ant 模式或具体文件路径，支持 classpath:、classpath*:、file: 与绝对路径，
 * 经 PathMatchingResourcePatternResolver 解析。模式零匹配属正常情况，静默返回空列表；
 * 具体路径的资源必须可读，打开或读取失败抛 UncheckedIOException，由自动加载转为启动失败。
 * 匹配按资源 URL 排序保证确定性；文件名不以 .md 结尾的资源忽略。本类不可变、线程安全，
 * 每次 load() 重新扫描；重叠位置按 URL 去重，读取至预算加一字节即停止。
 */
public final class LocalMarkdownFlowSource implements FlowSource {
    private final List<String> locations;
    private final ResourcePatternResolver resolver;
    private final FlowLoadingLimits limits;

    /**
     * 使用默认解析器创建本地来源。
     *
     * @param locations Ant 模式或具体文件路径列表，可为空列表
     */
    public LocalMarkdownFlowSource(List<String> locations) {
        this(locations, FlowLoadingLimits.defaults());
    }

    /**
     * 使用指定解析器创建本地来源，便于测试注入。
     *
     * @param locations Ant 模式或具体文件路径列表
     * @param resolver 资源模式解析器
     */
    LocalMarkdownFlowSource(List<String> locations, ResourcePatternResolver resolver) {
        this(locations, resolver, FlowLoadingLimits.defaults());
    }

    /**
     * 使用明确预算创建本地来源。
     *
     * @param locations 扫描位置，最多 32 项；重叠位置按资源 URL 去重
     * @param limits 读取预算，非 null
     */
    public LocalMarkdownFlowSource(List<String> locations, FlowLoadingLimits limits) {
        this(locations, new PathMatchingResourcePatternResolver(), limits);
    }

    LocalMarkdownFlowSource(List<String> locations, ResourcePatternResolver resolver, FlowLoadingLimits limits) {
        this.locations = List.copyOf(locations);
        this.resolver = Objects.requireNonNull(resolver);
        this.limits = Objects.requireNonNull(limits);
        if (locations.size() > 32) {
            throw FlowLoadingLimits.exceeded("locations");
        }
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public List<FlowDocument> load() {
        Map<String, Resource> matched = new TreeMap<>();
        for (String location : locations) {
            if (location.isBlank() || (location.contains(":") && !location.startsWith("file:")
                    && !location.startsWith("classpath:") && !location.startsWith("classpath*:"))) {
                throw new IllegalArgumentException("Only local flow locations are supported: " + location);
            }
            try {
                for (Resource resource : resolver.getResources(location)) {
                    String filename = resource.getFilename();
                    if (filename != null && filename.endsWith(".md")) {
                        matched.putIfAbsent(urlOf(resource), resource);
                        if (matched.size() > limits.maxDocuments()) {
                            throw FlowLoadingLimits.exceeded(location);
                        }
                    }
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("Failed to resolve flow location " + location, exception);
            }
        }
        List<FlowDocument> documents = new ArrayList<>();
        long total = 0;
        for (Resource resource : matched.values()) {
            String content = read(resource, Math.min(limits.maxDocumentBytes(), limits.maxTotalBytes() - total));
            total += limits.bytes(content, limits.maxDocumentBytes(), urlOf(resource));
            documents.add(new FlowDocument(urlOf(resource), content));
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

    private String read(Resource resource, long budget) {
        try (InputStream input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes((int) budget + 1);
            if (bytes.length > budget) {
                throw FlowLoadingLimits.exceeded(urlOf(resource));
            }
            // 严格解码，损坏的 UTF-8 不静默替换为其他字符。
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read flow resource " + urlOf(resource), exception);
        }
    }
}
