package io.github.mchgood.flow.result;

/**
 * 区分“没有输出”和“成功返回 null”的祖先输出视图。
 * <p>value 为业务对象引用，不做深拷贝。
 *
 * @param present true 表示节点成功，哪怕返回值是 null；false 表示没有成功输出
 * @param value 业务输出；present 为 false 时不应将其当作有效结果
 */
public record NodeOutput(boolean present, Object value) {}
