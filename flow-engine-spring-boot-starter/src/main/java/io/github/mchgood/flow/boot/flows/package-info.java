/**
 * 流程文件自动加载：按一级标题切分多流程 Markdown，从可配置位置读取并在单例就绪后
 * 原子注册；只注册、绝不执行，失败使应用启动失败。外部数据源通过实现 spi 的
 * FlowSource 接入，无需修改本包。
 */
package io.github.mchgood.flow.boot.flows;
