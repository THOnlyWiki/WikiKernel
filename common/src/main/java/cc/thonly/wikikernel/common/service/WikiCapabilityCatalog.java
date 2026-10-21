package cc.thonly.wikikernel.common.service;

/**
 * 站点能力目录（core 汇总内置语法 + 已加载插件提供的能力，供 AI Agent / 诊断页使用）。
 * <p>
 * 实现由 core 提供；插件只依赖这个 common 接口即可读取，不需要知道具体插件列表。
 */
public interface WikiCapabilityCatalog {

    /**
     * 适合直接拼进 AI 系统提示的能力目录文本。内容应包含当前站点实际可用的解析器标签、
     * 解析器函数、魔术字、特殊页与扩展/插件的简短用法；没有内容时返回空串。
     */
    String promptSection();
}
