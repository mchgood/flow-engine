package io.github.mchgood.flow.spi;

/**
 * 一份待解析的流程文档原文及其来源标识。
 * <p>不可变值对象。sourceName 仅用于错误定位与冲突提示（如资源 URL、外部配置 dataId），
 * 不参与编译语义；markdown 为整份文档原文，由加载器按一级标题切分。
 *
 * @param sourceName 来源标识，非空
 * @param markdown 整份文档原文，UTF-8 文本，非空
 */
public record FlowDocument(String sourceName, String markdown) {
}
