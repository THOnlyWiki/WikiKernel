package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.util.Strings;

import java.util.Set;

/**
 * WikiKernel（界面）命名空间里的配置页（仿 MediaWiki: 命名空间的界面消息页）：
 * <ul>
 *     <li>{@link #TITLE_BLACKLIST}：禁用标题名单（仿 {@code MediaWiki:Titleblacklist}）；</li>
 *     <li>{@link #TITLE_WHITELIST}：标题白名单（仿 {@code MediaWiki:Titlewhitelist}）；</li>
 *     <li>{@link #EMAIL_WHITELIST}：编辑邮箱白名单（仿 音MAD维基 的 {@code MediaWiki:Email-whitelist}）；</li>
 *     <li>{@link #EMAIL_BLACKLIST}：编辑邮箱黑名单（仿 MediaWiki 的 {@code MediaWiki:Email-blacklist}）。</li>
 * </ul>
 * 默认只有管理员 / 行政员能编辑整个 WikiKernel 命名空间（可在 {@code 特殊:权限配置} 用
 * {@code WikiKernel:*} 等规则覆盖，见 {@code AccessControlServiceImpl}）；
 * 标题黑 / 白名单页默认只有管理员能查看（其他人按「页面不存在」处理，不暴露规则内容）。
 */
public final class ConfigPages {

    /** 禁用标题名单（仿 MediaWiki:Titleblacklist）：命中的标题禁止创建 / 移动（{@code <noedit>} 也禁止编辑）。 */
    public static final String TITLE_BLACKLIST = "WikiKernel:Titleblacklist";

    /** 标题白名单（仿 MediaWiki:Titlewhitelist）：命中的标题不受禁用标题名单限制。 */
    public static final String TITLE_WHITELIST = "WikiKernel:Titlewhitelist";

    /** 编辑邮箱白名单（仿 MediaWiki:Email-whitelist）：只有匹配名单的邮箱才能保存编辑。 */
    public static final String EMAIL_WHITELIST = "WikiKernel:Email-whitelist";

    /** 编辑邮箱黑名单（仿 MediaWiki:Email-blacklist）：匹配名单的邮箱不能保存编辑。 */
    public static final String EMAIL_BLACKLIST = "WikiKernel:Email-blacklist";

    /** 默认只有管理员能查看的配置页（无权者按 404 处理）；可用查看规则覆盖。 */
    private static final Set<String> HIDDEN_WHEN_UNREADABLE = Set.of(TITLE_BLACKLIST, TITLE_WHITELIST);

    private ConfigPages() {
    }

    /** 该标题是否属于「无权查看时按页面不存在处理」（而不是 403）的配置页。 */
    public static boolean hiddenWhenUnreadable(String title) {
        String value = Strings.trimToEmpty(title);
        return HIDDEN_WHEN_UNREADABLE.stream().anyMatch(page -> page.equalsIgnoreCase(value));
    }
}
