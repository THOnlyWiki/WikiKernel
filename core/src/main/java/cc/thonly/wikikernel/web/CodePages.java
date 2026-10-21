package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.util.Strings;

import java.util.Locale;

/**
 * 代码页面（CSS / JS）工具。
 * <p>
 * 仿 MediaWiki 的 {@code interface-admin}：CSS / JS / TypeScript / Vue 页面默认只有「界面管理员」能编辑 / 创建 / 移动
 * （默认值在 {@code AccessControlServiceImpl#builtinGroups}，可用访问规则覆盖，例如为单个页面加
 * {@code 页面.js → 编辑 = 用户} 的精确规则）。
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
