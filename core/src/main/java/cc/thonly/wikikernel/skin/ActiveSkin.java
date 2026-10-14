package cc.thonly.wikikernel.skin;

import java.util.List;

/**
 * 当前请求生效的皮肤视图（注入模板：{@code ${skin.layout}}、{@code ${skin.stylesheets}} 等）。
 * 模板统一用 {@code ~{__${skin.layout}__ :: layout(...)}} 套外壳。
 */
public record ActiveSkin(String id,
                         String layout,
                         List<String> stylesheets,
                         List<String> scripts,
                         String logoUrl,
                         String name,
                         String description,
                         String version) {
}
