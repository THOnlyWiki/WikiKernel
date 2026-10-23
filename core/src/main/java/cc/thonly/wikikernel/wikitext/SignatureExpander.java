package cc.thonly.wikikernel.wikitext;

import cc.thonly.wikikernel.common.data.WikiUser;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MediaWiki 风格签名展开：{@code ~~~} / {@code ~~~~} / {@code ~~~~~}。
 * <p>
 * 签名只在**提交 / 保存时**展开（与 MediaWiki 一致），使用本次提交的用户；
 * 段落编辑只展开新提交的片段，不会按历史版本作者回填别人的签名。
 * <p>
 * 不处理 {@code <nowiki>} / {@code <pre>} / {@code <code>} / {@code <syntaxhighlight>}
 * 等字面量块与 HTML 注释里的波浪号。
 */
public final class SignatureExpander {

    private static final Pattern TILDES = Pattern.compile("~{3,5}");

    private static final Pattern LITERAL_BLOCK = Pattern.compile(
            "(?is)<(nowiki|pre|code|syntaxhighlight|source|templatedata)\\b[^>]*>.*?</\\1\\s*>"
                    + "|<!--.*?-->");

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final char PLACEHOLDER_START = '\uE100';
    private static final char PLACEHOLDER_END = '\uE101';

    private SignatureExpander() {
    }

    /** 用户对象入口；{@code user} 为空时按匿名处理。 */
    public static String expand(String wikitext, WikiUser user, LocalDateTime timestamp) {
        if (user == null) {
            return expand(wikitext, "", "", false, timestamp);
        }
        return expand(wikitext,
                user.getUsername() == null ? "" : user.getUsername(),
                user.getSignature() == null ? "" : user.getSignature(),
                Boolean.TRUE.equals(user.getSignatureWikitext()),
                timestamp);
    }

    /**
     * 展开展示用签名。
     *
     * @param username          用户名（用于默认签名 / 自动链接）
     * @param signature         自定义签名；空 = 用户名
     * @param signatureWikitext 自定义签名是否按 wikitext 原样使用
     *                          （false 时自动链接到用户页，与参数设置说明一致）
     */
    public static String expand(String wikitext, String username, String signature,
                                boolean signatureWikitext, LocalDateTime timestamp) {
        if (wikitext == null || wikitext.indexOf('~') < 0) {
            return wikitext == null ? "" : wikitext;
        }
        String stamp = timestamp == null ? "" : timestamp.format(TIMESTAMP_FORMAT);
        String signatureMarkup = signatureMarkup(username, signature, signatureWikitext);

        // 先保护字面量块，避免把 <nowiki>~~~</nowiki> 或代码里的 ~ 也展开。
        List<String> literals = new ArrayList<>();
        Matcher literalMatcher = LITERAL_BLOCK.matcher(wikitext);
        StringBuffer protectedText = new StringBuffer();
        while (literalMatcher.find()) {
            literals.add(literalMatcher.group());
            literalMatcher.appendReplacement(protectedText,
                    Matcher.quoteReplacement(placeholder(literals.size() - 1)));
        }
        literalMatcher.appendTail(protectedText);

        Matcher tildeMatcher = TILDES.matcher(protectedText.toString());
        StringBuffer expanded = new StringBuffer();
        while (tildeMatcher.find()) {
            String replacement = switch (tildeMatcher.group().length()) {
                case 3 -> signatureMarkup;
                case 4 -> signatureMarkup + (stamp.isEmpty() ? "" : " " + stamp);
                default -> stamp;
            };
            tildeMatcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
        }
        tildeMatcher.appendTail(expanded);

        String result = expanded.toString();
        for (int i = 0; i < literals.size(); i++) {
            result = result.replace(placeholder(i), literals.get(i));
        }
        return result;
    }

    private static String placeholder(int index) {
        return PLACEHOLDER_START + Integer.toString(index) + PLACEHOLDER_END;
    }

    /** 生成签名片段（不含时间戳）。 */
    static String signatureMarkup(String username, String signature, boolean signatureWikitext) {
        String name = username == null ? "" : username.trim();
        String custom = signature == null ? "" : signature.trim();
        if (custom.isEmpty()) {
            return name.isEmpty() ? "匿名" : "[[" + userPage(name) + "|" + escapeLinkLabel(name) + "]]";
        }
        if (signatureWikitext) {
            return custom;
        }
        return name.isEmpty()
                ? escapeLinkLabel(custom)
                : "[[" + userPage(name) + "|" + escapeLinkLabel(custom) + "]]";
    }

    private static String userPage(String username) {
        return "用户:" + username;
    }

    private static String escapeLinkLabel(String value) {
        return value.replace("|", "&#124;")
                .replace("[", "&#91;")
                .replace("]", "&#93;");
    }
}
