package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.util.Strings;

import java.util.Locale;

/**
 * 代码页面（CSS / JS）工具。
 * <p>
 * 仿 MediaWiki 的 {@code interface-admin}：CSS / JS / TypeScript / Vue 页面默认只有「界面管理员」能编辑 / 创建 / 移动
 * （判定在 {@code AccessControlServiceImpl#requiredGroups}；全局 {@code *} / 前缀规则不会绕过，
 * 只有 {@code 页面.js → 编辑 = 用户} 这种针对单个页面的精确规则可以显式覆盖）。
 */
public final class CodePages {

    private CodePages() {
    }

    /** 标题是否以 {@code .css} / {@code .js} / {@code .ts} / {@code .tsx} / {@code .vue} 结尾（大小写不敏感；含子页面与全站脚本）。 */
    public static boolean isInterfacePage(String title) {
        String lower = Strings.trimToEmpty(title).toLowerCase(Locale.ROOT);
        return lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".ts")
                || lower.endsWith(".tsx") || lower.endsWith(".vue");
    }
}
