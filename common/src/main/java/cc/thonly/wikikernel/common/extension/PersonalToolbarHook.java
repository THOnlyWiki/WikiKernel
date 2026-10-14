package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：顶栏个人工具区（{@code templates/fragments/personal-tools.html}）的额外条目
 * （仿 MediaWiki 的 {@code SkinTemplateNavigation} / {@code PersonalUrls} 钩子）。
 * <p>
 * core 在**所有渲染模板的页面**（特殊页 / 编辑页 / 历史页等，API 除外）按 {@link #order()} 升序调用，
 * 把返回的 {@code <li>…</li>} 追加在个人工具区的最后一项之后（例如「私信（3）」未读计数）。
 * <p>
 * 约定：返回的 HTML 由 core 原样插入（{@code th:utext}），插件必须自行转义用户输入；
 * {@code null} / 空串 = 不插入；抛异常只影响该插件，core 会跳过并继续其它插件（页面不 500）；
 * 没有任何插件注册时个人工具区与迁移前完全一致。
 */
public interface PersonalToolbarHook extends Extension {

    /** 执行顺序，值越小越先执行。 */
    default int order() {
        return 0;
    }

    /**
     * 个人工具区的额外条目 HTML（{@code <li>…</li>} 级别，含自己的链接与文案）。
     *
     * @return HTML；{@code null} / 空串 = 不插入
     */
    default String itemsHtml(PersonalToolbarContext context) {
        return null;
    }
}
