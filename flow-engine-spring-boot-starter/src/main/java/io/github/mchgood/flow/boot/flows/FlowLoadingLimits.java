package io.github.mchgood.flow.boot.flows;

import io.github.mchgood.flow.exception.FlowException;

import java.nio.charset.StandardCharsets;

/**
 * 启动加载的不可变资源预算，内置来源读取与所有来源注册共用。
 * <p>来源自身必须限制读取和返回值分配；注册器只能在自定义来源返回后检查其内容。
 * 原文累计预算与切分后的注册文本累计预算分别计数，防止行号填充放大内存。
 *
 * @param maxDocumentBytes 单份原文 UTF-8 字节上限，1 至 1 MiB
 * @param maxTotalBytes 原文及展开文本各自的批次字节上限，不小于单文档且至多 256 MiB
 * @param maxDocuments 批次唯一文档数上限，1 至 1024
 * @param maxFlows 批次流程数上限，1 至 4096
 */
public record FlowLoadingLimits(int maxDocumentBytes, int maxTotalBytes, int maxDocuments, int maxFlows) {
    /**
     * 校验启动资源预算。
     *
     * @throws IllegalArgumentException 预算超出允许范围
     */
    public FlowLoadingLimits {
        if (maxDocumentBytes < 1 || maxDocumentBytes > 1024 * 1024
                || maxTotalBytes < maxDocumentBytes || maxTotalBytes > 256 * 1024 * 1024
                || maxDocuments < 1 || maxDocuments > 1024 || maxFlows < 1 || maxFlows > 4096) {
            throw new IllegalArgumentException("Invalid flow loading limits");
        }
    }

    /**
     * 返回默认预算：单份 1 MiB、批次 16 MiB、128 文档、256 流程。
     *
     * @return 不可变默认预算
     */
    public static FlowLoadingLimits defaults() {
        return new FlowLoadingLimits(1024 * 1024, 16 * 1024 * 1024, 128, 256);
    }

    /**
     * 在转换字节前先检查字符数，减少明显过大自定义内容的额外分配。
     *
     * @param text 文档内容，非 null
     * @param limit 字节预算
     * @param source 来源标识
     * @return 精确 UTF-8 字节数
     * @throws FlowException 内容缺失或超出预算，错误码 FLOW_LOADING_LIMIT
     */
    int bytes(String text, long limit, String source) {
        if (text == null || text.length() > limit) {
            throw exceeded(source);
        }
        int size = text.getBytes(StandardCharsets.UTF_8).length;
        if (size > limit) {
            throw exceeded(source);
        }
        return size;
    }

    /**
     * 生成统一的加载超限错误，不输出业务原文。
     */
    static FlowException exceeded(String source) {
        return new FlowException("FLOW_LOADING_LIMIT", "Flow loading budget exceeded: " + source);
    }
}
