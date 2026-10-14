package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.EmailBlacklistService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.ConfigPages;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 编辑邮箱黑名单实现：解析 {@code WikiKernel:Email-blacklist} 的原始 wikitext。
 * <p>
 * 格式（与「编辑邮箱白名单」相同）：每行一条正则片段（按「包含」匹配邮箱地址，不区分大小写），
 * 以 {@code #} 开头的行是注释；页面没有规则时黑名单不生效（任何人都能保存）。
 * 每次检查都读名单页最新版本，改完立即生效。
 */
@Service
public class EmailBlacklistServiceImpl implements EmailBlacklistService {

    private final WikiPageService wikiPageService;

    public EmailBlacklistServiceImpl(WikiPageService wikiPageService) {
        this.wikiPageService = wikiPageService;
    }

    @Override
    public boolean enabled() {
        return !rules().isEmpty();
    }

    @Override
    public boolean blocked(String email) {
        List<Pattern> rules = rules();
        if (rules.isEmpty()) {
            return false;
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
        String content = wikiPageService.getByTitle(ConfigPages.EMAIL_BLACKLIST)
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
