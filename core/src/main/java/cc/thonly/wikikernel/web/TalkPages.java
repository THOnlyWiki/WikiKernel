package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.util.Strings;

import java.util.List;

/**
 * 讨论命名空间映射（仿 MediaWiki）：每个内容页面都有对应的讨论页。
 * <ul>
 *     <li>主命名空间：{@code 首页} ↔ {@code 讨论:首页}；</li>
 *     <li>已有命名空间：{@code 用户:X} ↔ {@code 用户讨论:X}、{@code 分类:X} ↔ {@code 分类讨论:X}、
 *     {@code 模板:X} / {@code 文件:X} / {@code 帮助:X} / {@code WikiKernel:X} 同理（{@code User:} 等英文别名归一到中文讨论前缀）。</li>
 * </ul>
 * 讨论页就是普通 Wiki 页面，可以创建 / 编辑 / 查看历史。
 */
public final class TalkPages {

    /** 主题命名空间前缀 → 讨论命名空间前缀（大小写不敏感）。 */
    private static final List<String[]> NS_PAIRS = List.of(
            new String[]{"用户:", "用户讨论:"},
            new String[]{"User:", "用户讨论:"},
            new String[]{"分类:", "分类讨论:"},
            new String[]{"Category:", "分类讨论:"},
            new String[]{"模板:", "模板讨论:"},
            new String[]{"Template:", "模板讨论:"},
            new String[]{"文件:", "文件讨论:"},
            new String[]{"File:", "文件讨论:"},
            new String[]{"帮助:", "帮助讨论:"},
            new String[]{"微件:", "微件讨论:"},
            new String[]{"Widget:", "微件讨论:"},
            new String[]{"WikiKernel:", "WikiKernel讨论:"});

    /** 主命名空间的讨论前缀。 */
    private static final String MAIN_TALK_PREFIX = "讨论:";

    private TalkPages() {
    }

    /** 是否是讨论页（{@code 讨论:X} / {@code 用户讨论:X} / …）。 */
    public static boolean isTalk(String title) {
        return talkPrefixOf(title) != null;
    }

    /** 页面的讨论页标题；讨论页自身返回 null（讨论页不再有讨论页）。 */
    public static String talkTitle(String title) {
        String value = Strings.trimToEmpty(title);
        if (value.isEmpty() || isTalk(value)) {
            return null;
        }
        for (String[] pair : NS_PAIRS) {
            if (value.regionMatches(true, 0, pair[0], 0, pair[0].length())) {
                return pair[1] + value.substring(pair[0].length());
            }
        }
        return MAIN_TALK_PREFIX + value;
    }

    /** 讨论页对应的主题页标题；非讨论页原样返回。 */
    public static String subjectTitle(String title) {
        String value = Strings.trimToEmpty(title);
        for (String[] pair : NS_PAIRS) {
            if (value.regionMatches(true, 0, pair[1], 0, pair[1].length())) {
                return pair[0] + value.substring(pair[1].length());
            }
        }
        if (value.regionMatches(true, 0, MAIN_TALK_PREFIX, 0, MAIN_TALK_PREFIX.length())) {
            return value.substring(MAIN_TALK_PREFIX.length());
        }
        return value;
    }

    /** 标题命中的讨论前缀（无则 null）。 */
    private static String talkPrefixOf(String title) {
        String value = Strings.trimToEmpty(title);
        for (String[] pair : NS_PAIRS) {
            if (value.regionMatches(true, 0, pair[1], 0, pair[1].length())) {
                return pair[1];
            }
        }
        return value.regionMatches(true, 0, MAIN_TALK_PREFIX, 0, MAIN_TALK_PREFIX.length())
                ? MAIN_TALK_PREFIX : null;
    }
}
