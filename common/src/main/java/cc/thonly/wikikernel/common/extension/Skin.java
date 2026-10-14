package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

import java.util.List;

/**
 * 皮肤扩展点（仿 MediaWiki Skin）：
 * 一个皮肤 = 页面外壳（layout 模板）+ 自己的样式（可带脚本 / logo）。
 * <p>
 * core 内置 {@code vector}（默认）与 {@code fallback}（兜底，不可禁用）；
 * 插件可通过 {@code PluginContext.register(Skin.class, ...)} 注册，模板与样式放插件 jar 里：
 * <ul>
 *     <li>layout：{@code templates/skins/<id>/layout.html}（{@code th:fragment="layout(pageTitle, content)"}）；</li>
 *     <li>样式 / 脚本 / 图片：{@code static/skins/<id>/...}，由 core 的 {@code /skins/<id>/<file>} 端点
 *     从皮肤所属类加载器读取（内置皮肤直接从 classpath 读）。</li>
 * </ul>
 * 通用组件样式由 core 的 {@code /css/base.css} 提供，皮肤只负责外壳，可自行覆盖。
 */
public interface Skin extends Extension {

    /** 皮肤 id（小写字母 / 数字 / 连字符，作为 URL 与配置键）。 */
    String id();

    /** 展示名默认值（本地化优先走消息键 {@code skin.<id>.name}）。 */
    default String displayName() {
        return id();
    }

    /** 说明默认值（本地化优先走消息键 {@code skin.<id>.description}）。 */
    default String description() {
        return "";
    }

    /** 皮肤版本（显示在 特殊:版本）。 */
    default String version() {
        return "";
    }

    /** layout 模板名（相对 templates/）。 */
    default String layout() {
        return "skins/" + id() + "/layout";
    }

    /** 皮肤样式表 URL（相对站点根；注入在 base.css 之后）。 */
    default List<String> stylesheets() {
        return List.of("/skins/" + id() + "/" + id() + ".css");
    }

    /** 皮肤附加脚本 URL（可选）。 */
    default List<String> scripts() {
        return List.of();
    }

    /** 顶栏 logo 图片 URL（layout 模板按需使用）。 */
    default String logoUrl() {
        return "/img/wiki-logo.svg";
    }

    /** 排序（越小越靠前）。 */
    default int order() {
        return 100;
    }
}
