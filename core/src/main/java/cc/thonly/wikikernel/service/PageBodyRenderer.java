package cc.thonly.wikikernel.service;

/**
 * 页面正文渲染（渲染管线的唯一入口，供页面缓存与预渲染复用）：
 * Wikitext → HTML + 渲染元信息（显示标题 / 分类 / 跨语言链接 / 页面属性），
 * 并依次执行插件的 {@code PageRenderHook}。
 * <p>
 * 与具体用户相关的处理（段落编辑链接等）<b>不在这里</b>：那些必须每次请求重新注入，
 * 否则会把某个用户看到的内容缓存给所有人。
 */
public interface PageBodyRenderer {

    /**
     * 渲染页面正文（每次都真的渲染，不做缓存；缓存请走 {@link PageCacheService#body}）。
     *
     * @param title      页面标题（渲染上下文 / 魔术字 / 模板解析都用它）
     * @param content    最新版本的 wikitext 正文
     * @param revisionId 产出对应的版本号（写进 {@link PageBody}，缓存命中时比对）
     */
    PageBody render(String title, String content, long revisionId);
}
