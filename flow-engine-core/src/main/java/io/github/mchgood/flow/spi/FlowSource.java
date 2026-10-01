package io.github.mchgood.flow.spi;

import java.util.List;

/**
 * 流程文档来源扩展点。实现方提供一批 Markdown 文档，由自动加载器统一按一级标题切分注册。
 * <p>实现需线程安全且可被多次调用；每次调用返回当时的完整文档集，框架不缓存返回值。
 * 内置实现见 starter 的 LocalMarkdownFlowSource；外部数据源（如 Nacos）实现本接口即可接入。
 *
 * @see FlowDocument
 */
public interface FlowSource {

    /**
     * 来源实例名，用于跨来源冲突与失败信息。
     *
     * @return 稳定的非空名称
     */
    String name();

    /**
     * 加载全部文档。
     *
     * @return 文档列表，可为空列表，元素非 null
     * @throws RuntimeException 读取失败时抛出非受检异常，导致自动加载启动失败
     */
    List<FlowDocument> load();
}
