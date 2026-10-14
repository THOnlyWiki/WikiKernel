package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;

import java.util.Optional;

/**
 * 禁用标题名单（仿 MediaWiki 的 TitleBlacklist 扩展，规则来自 {@code WikiKernel:Titleblacklist} 页面）。
 * <p>
 * 规则语法（与 MediaWiki 相同）：
 * <ul>
 *     <li>每行一条规则；行内 {@code #} 起为注释；</li>
 *     <li>{@code 正则表达式 [<参数1|参数2>]}：对整个标题做完全匹配（{@code ^(?:正则)$}），
 *     默认不区分大小写、支持 Unicode；下划线会当作空格；</li>
 *     <li>参数：{@code noedit}（也禁止编辑已有页面）、{@code moveonly}（只禁止移动）、
 *     {@code newaccountonly}（只用于注册用户名）、{@code casesensitive}（区分大小写）、
 *     {@code autoconfirmed}（自动确定用户不受此规则限制）、{@code errmsg=消息键}（自定义错误提示）；</li>
 *     <li>{@code WikiKernel:Titlewhitelist} 命中的标题不受任何黑名单规则限制；</li>
 *     <li>管理员 / 行政员不受限制（仿 MediaWiki 的 tboverride）。</li>
 * </ul>
 * 每次检查都读取名单页的最新版本（改完立即生效）。
 */
public interface TitleBlacklistService {

    /** 检查的动作（决定哪些规则生效，与 MediaWiki 一致）。 */
    enum Operation {
        /** 创建新页面：普通规则生效。 */
        CREATE,
        /** 编辑已有页面：只有带 {@code noedit} 的规则生效。 */
        EDIT,
        /** 移动页面：普通规则与 {@code moveonly} 规则生效。 */
        MOVE,
        /** 注册账号：只有带 {@code newaccountonly} 的规则生效。 */
        NEW_ACCOUNT
    }

    /**
     * 命中的规则。
     *
     * @param pattern          正则原文（用于提示 / 排查）
     * @param customMessageKey {@code errmsg=消息键} 指定的消息键（可能为空 = 用内置提示）
     */
    record Violation(String pattern, String customMessageKey) {
    }

    /** 检查标题是否被禁止；白名单命中 / 用户豁免时返回空。 */
    Optional<Violation> check(String title, Operation operation, WikiUser user);
}
