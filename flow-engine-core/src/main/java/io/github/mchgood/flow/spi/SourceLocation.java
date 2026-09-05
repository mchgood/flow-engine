package io.github.mchgood.flow.spi;

/**
 * 定义源码中的诊断位置；由解析器把 Mermaid 行号映射回 Markdown。
 * <p>此值对象不校验坐标范围，调用方应提供基于 1 的行列。
 *
 * @param source 来源标识；字符串注册入口使用 flowId
 * @param line 基于 1 的 Markdown 行号
 * @param column 基于 1 的列号
 */
public record SourceLocation(String source, int line, int column) {

    /**
     * 格式化诊断坐标。
     *
     * @return source:line:column 格式的位置
     */
    @Override public String toString() { return source+":"+line+":"+column; }
}
