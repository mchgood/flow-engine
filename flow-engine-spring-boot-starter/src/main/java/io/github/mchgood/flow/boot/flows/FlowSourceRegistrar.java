package io.github.mchgood.flow.boot.flows;

import io.github.mchgood.flow.api.FlowEngine;
import io.github.mchgood.flow.exception.FlowException;
import io.github.mchgood.flow.spi.FlowDocument;
import io.github.mchgood.flow.spi.FlowSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 汇总全部 FlowSource 文档，按一级标题切分后原子注册到引擎。
 * <p>SmartInitializingSingleton 时机：全部单例就绪后执行一次，因此宿主覆盖 FlowEngine
 * Bean 时自动加载依然生效；无引擎 Bean 时静默跳过。跨来源 flowId 冲突立即失败并附两个
 * 来源名；注册经单次 registerAll 原子发布，跨文件、跨来源子流程引用同批可用。只注册、
 * 绝不执行。仅在单例初始化阶段执行一次，无线程安全问题。
 * <p>限制：与宿主手动注册共用同一命名空间，同 flowId 手动注册会导致 DUPLICATE_FLOW
 * 启动失败，由宿主保证不冲突。
 */
public final class FlowSourceRegistrar implements SmartInitializingSingleton {
    private final ObjectProvider<FlowEngine> engines;
    private final ObjectProvider<FlowSource> sources;
    private final MarkdownFlowParser parser = new MarkdownFlowParser();

    /**
     * 创建注册器。
     *
     * @param engines 引擎提供者，可为空（无引擎时静默跳过）
     * @param sources 来源提供者，可为空
     */
    public FlowSourceRegistrar(ObjectProvider<FlowEngine> engines, ObjectProvider<FlowSource> sources) {
        this.engines = engines;
        this.sources = sources;
    }

    @Override
    public void afterSingletonsInstantiated() {
        FlowEngine engine = engines.getIfAvailable();
        if (engine == null) {
            return;
        }
        Map<String, String> flows = new LinkedHashMap<>();
        Map<String, String> origins = new LinkedHashMap<>();
        for (FlowSource source : sources.orderedStream().toList()) {
            for (FlowDocument document : source.load()) {
                Map<String, String> parsed = parser.split(document.sourceName(), document.markdown());
                String origin = source.name() + ":" + document.sourceName();
                for (Map.Entry<String, String> entry : parsed.entrySet()) {
                    if (flows.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                        throw new FlowException("DUPLICATE_FLOW",
                            "Flow \"" + entry.getKey() + "\" defined in both " + origins.get(entry.getKey())
                                + " and " + origin);
                    }
                    origins.put(entry.getKey(), origin);
                }
            }
        }
        if (flows.isEmpty()) {
            return;
        }
        try {
            engine.registerAll(flows);
        } catch (FlowException exception) {
            throw new FlowException(exception.code(),
                exception.getMessage() + "; flows loaded from sources: " + origins);
        }
    }
}
