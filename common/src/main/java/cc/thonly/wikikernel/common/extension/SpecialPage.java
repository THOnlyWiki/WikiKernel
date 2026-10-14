package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

import java.util.List;
import java.util.Map;

/**
 * 特殊页面扩展点（类似 MediaWiki 的 {@code Special:XXX} / {@code 特殊:XXX}）。
 * <p>
 * 新增特殊页 = 新增一个实现（core 里 {@code @Component}，或插件里
 * {@code PluginContext.register(SpecialPage.class, ...)}），不用改任何路由。
 * <p>
 * 默认走模板：实现 {@link #name()} 和 {@link #populate} 即可，
 * 模板名默认 {@code special/view}，可覆盖 {@link #template()}。
 * 插件模板放在插件 jar 的 {@code templates/} 下即可被解析（core 会从插件类加载器读取）。
 * 简单页面也可以覆盖 {@link #render} 直接返回 HTML。
 */
public interface SpecialPage extends Extension {

    /**
     * {@code populate()} 里可设置的模型键：值为站内路径（如 {@code /wiki/标题?action=edit}）。
     * 控制器发现该键后会直接 302 跳转，而不是渲染模板——用于“创建页面”这类纯跳转特殊页。
     */
    String REDIRECT_MODEL_ATTRIBUTE = "specialRedirect";

    /** 主名称（不带前缀），如 "全部页面"。也是 URL 里使用的名字。 */
    String name();

    /** 展示名（可本地化，比如从 MessageSource 取）；默认与 {@link #name()} 相同。 */
    default String displayName() {
        return name();
    }

    /** 别名（不带前缀），如 "AllPages"，用于英文/旧链接。 */
    default List<String> aliases() {
        return List.of();
    }

    /**
     * 分类 id（{@code 特殊:特殊页面} 里的分组，见 {@link SpecialPageCategories}）；
     * 默认 {@code other}（未分类的直接归到「其他特殊页面」）。
     */
    default String category() {
        return SpecialPageCategories.OTHER;
    }

    /** 模板名（相对 templates/），默认统一外壳 special/view。 */
    default String template() {
        return "special/view";
    }

    /** 填充模板模型；模板里用 {@code ${...}} 读取。 */
    default void populate(SpecialPageRequest request, Map<String, Object> model) {
    }

    /** 可选：直接返回 HTML；非 null 时优先于模板，并套用 special/view 外壳输出。 */
    default String render(SpecialPageRequest request) {
        return null;
    }
}
