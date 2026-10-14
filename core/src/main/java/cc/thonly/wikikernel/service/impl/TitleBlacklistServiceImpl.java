package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.TitleBlacklistService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.ConfigPages;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 禁用标题名单实现：解析 {@code WikiKernel:Titleblacklist} / {@code WikiKernel:Titlewhitelist}
 * 的原始 wikitext（与 MediaWiki 的 TitleBlacklist 扩展同一套语法）。
 */
@Service
public class TitleBlacklistServiceImpl implements TitleBlacklistService {

    private final WikiPageService wikiPageService;
    private final PermissionService permissionService;

    public TitleBlacklistServiceImpl(WikiPageService wikiPageService,
                                     PermissionService permissionService) {
        this.wikiPageService = wikiPageService;
        this.permissionService = permissionService;
    }

    @Override
    public Optional<Violation> check(String title, Operation operation, WikiUser user) {
        String value = Strings.trimToEmpty(title);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        // 管理员 / 行政员不受限制（仿 MediaWiki 的 tboverride）
        if (canOverride(user)) {
            return Optional.empty();
        }
        // 白名单优先：命中的标题不受黑名单限制
        if (matches(parse(content(ConfigPages.TITLE_WHITELIST)), value, operation)) {
            return Optional.empty();
        }
        for (Rule rule : parse(content(ConfigPages.TITLE_BLACKLIST))) {
            if (!rule.appliesTo(operation)) {
                continue;
            }
            if (rule.autoconfirmed() && isAutoconfirmed(user)) {
                continue;
            }
            if (rule.pattern().matcher(value).matches()) {
                return Optional.of(new Violation(rule.raw(), rule.customMessageKey()));
            }
        }
        return Optional.empty();
    }

    // ==================== 解析（与 MediaWiki TitleBlacklistEntry 对齐） ====================

    /** 解析名单页内容；非法正则整行忽略（不会让整份名单失效）。 */
    static List<Rule> parse(String content) {
        if (Strings.isBlank(content)) {
            return List.of();
        }
        List<Rule> rules = new ArrayList<>();
        for (String rawLine : content.split("\\R", -1)) {
            String line = rawLine;
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String regex = line;
            String options = "";
            if (line.endsWith(">")) {
                int open = line.lastIndexOf('<');
                if (open >= 0) {
                    regex = line.substring(0, open).trim();
                    options = line.substring(open + 1, line.length() - 1);
                }
            }
            regex = regex.replace('_', ' ').trim();
            if (regex.isEmpty()) {
                continue;
            }
            boolean noedit = false;
            boolean moveonly = false;
            boolean newaccountonly = false;
            boolean casesensitive = false;
            boolean autoconfirmed = false;
            String errmsg = null;
            for (String option : options.split("\\|")) {
                String optionValue = option.trim();
                String lower = optionValue.toLowerCase(Locale.ROOT);
                switch (lower) {
                    case "noedit" -> noedit = true;
                    case "moveonly" -> moveonly = true;
                    case "newaccountonly" -> newaccountonly = true;
                    case "casesensitive" -> casesensitive = true;
                    case "autoconfirmed" -> autoconfirmed = true;
                    default -> {
                        // 未知参数忽略（与 MediaWiki 一致）
                    }
                }
                if (lower.startsWith("errmsg=")) {
                    errmsg = optionValue.substring(optionValue.indexOf('=') + 1).trim();
                }
            }
            int flags = Pattern.DOTALL | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
            if (!casesensitive) {
                flags |= Pattern.CASE_INSENSITIVE;
            }
            try {
                rules.add(new Rule(Pattern.compile("^(?:" + regex + ")$", flags), regex,
                        noedit, moveonly, newaccountonly, autoconfirmed, errmsg));
            } catch (PatternSyntaxException exception) {
                // 非法正则：忽略该行
            }
        }
        return List.copyOf(rules);
    }

    /** 单条规则（对齐 MediaWiki TitleBlacklistEntry）。 */
    record Rule(Pattern pattern, String raw, boolean noedit, boolean moveonly,
                boolean newaccountonly, boolean autoconfirmed, String customMessageKey) {

        /** 该规则是否作用于指定动作（与 MediaWiki 的 matches() 相同的判断顺序）。 */
        boolean appliesTo(Operation operation) {
            if (moveonly && operation != Operation.MOVE) {
                return false;
            }
            if (newaccountonly && operation != Operation.NEW_ACCOUNT) {
                return false;
            }
            return noedit || operation != Operation.EDIT;
        }
    }

    private static boolean matches(List<Rule> rules, String title, Operation operation) {
        for (Rule rule : rules) {
            if (rule.appliesTo(operation) && rule.pattern().matcher(title).matches()) {
                return true;
            }
        }
        return false;
    }

    // ==================== 数据获取 ====================

    /** 名单页最新版本的原始内容（页面不存在 / 内容为空 = 没有规则；每次读取，改完立即生效）。 */
    private String content(String title) {
        Optional<WikiPage> page = wikiPageService.getByTitle(title);
        return page.map(value -> wikiPageService.getLatestContent(value.getId()).orElse("")).orElse("");
    }

    private boolean isAutoconfirmed(WikiUser user) {
        return user != null && permissionService.has(user.getId(), PermissionService.GROUP_AUTOCONFIRMED);
    }

    /** 管理员 / 行政员豁免（仿 MediaWiki 的 tboverride）。 */
    private boolean canOverride(WikiUser user) {
        return user != null && permissionService.canManage(user.getId());
    }
}
