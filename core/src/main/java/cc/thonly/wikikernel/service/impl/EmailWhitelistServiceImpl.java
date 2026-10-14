package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.EmailWhitelistService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.ConfigPages;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 编辑邮箱白名单实现：解析 {@code WikiKernel:Email-whitelist} 的原始 wikitext。
 * <p>
 * 格式（与 音MAD维基 的 MediaWiki:Email-whitelist 相同）：
 * 每行一条正则片段（按「包含」匹配邮箱地址，不区分大小写），以 {@code #} 开头的行是注释；
 * 页面没有规则时白名单不生效（任何人都能保存）。
 */
@Service
public class EmailWhitelistServiceImpl implements EmailWhitelistService {

    private final WikiPageService wikiPageService;

    public EmailWhitelistServiceImpl(WikiPageService wikiPageService) {
        this.wikiPageService = wikiPageService;
    }

    @Override
    public boolean enabled() {
        return !rules().isEmpty();
    }

    @Override
    public boolean allows(String email) {
        List<Pattern> rules = rules();
        if (rules.isEmpty()) {
            return true;
        }
        String value = Strings.trimToEmpty(email);
        if (value.isEmpty()) {
            return false;
        }
        for (Pattern rule : rules) {
            if (rule.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    /** 名单页最新版本里的正则片段（每次读取，改完立即生效；非法正则整行忽略）。 */
    private List<Pattern> rules() {
        String content = wikiPageService.getByTitle(ConfigPages.EMAIL_WHITELIST)
                .map(page -> wikiPageService.getLatestContent(page.getId()).orElse(""))
                .orElse("");
        if (Strings.isBlank(content)) {
            return List.of();
        }
        List<Pattern> rules = new ArrayList<>();
        for (String rawLine : content.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                rules.add(Pattern.compile(line, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (PatternSyntaxException exception) {
                // 非法正则：忽略该行
            }
        }
        return List.copyOf(rules);
    }
}
