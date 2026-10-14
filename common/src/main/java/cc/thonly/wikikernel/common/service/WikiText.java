package cc.thonly.wikikernel.common.service;

/**
 * Wikitext 渲染桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 与页面正文渲染走**同一套**渲染器（模板、解析器函数、插件标签、HTML 白名单全部生效），
 * 供评论区展示评论内容、审核 / 预览页渲染待审正文、AI 代理的预览等使用。
 */
public interface WikiText {

    /**
     * 把一段 wikitext 渲染成 HTML（不落盘、不改页面）。
     *
     * @param wikitext  正文
     * @param pageTitle 渲染时使用的页面标题（影响 {@code {{PAGENAME}}}、红链、模板标题等；可为空串）
     */
    String toHtml(String wikitext, String pageTitle);

    /**
     * 渲染成 HTML 并返回分类（模板带入的分类也在内），供需要展示分类栏的调用方使用。
     * 默认实现只返回正文 HTML（分类为空），需要的实现（core）会覆盖它。
     */
    default Rendered toHtmlWithCategories(String wikitext, String pageTitle) {
        return new Rendered(toHtml(wikitext, pageTitle), java.util.List.of());
    }

    /**
     * 渲染结果。
     *
     * @param html       正文 HTML
     * @param categories 分类标题（如 {@code 分类:示例}，已规范化）
     */
    record Rendered(String html, java.util.List<String> categories) {
    }
}
