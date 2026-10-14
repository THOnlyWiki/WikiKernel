package cc.thonly.wikikernel.transfer;

import cc.thonly.wikikernel.web.WikiNamespaces;

import java.util.List;

/**
 * MediaWiki 命名空间编号映射：本 wiki 的命名空间 id ↔ MediaWiki 标准编号
 * （siteinfo 的 {@code <ns>} 与 {@code <namespaces>} 用；导入时以标题前缀为准，不依赖编号）。
 */
public final class TransferNamespaces {

    /** siteinfo 里的一个命名空间（编号 + 展示名；主命名空间名为空）。 */
    public record Entry(int key, String name) {
    }

    /** MediaWiki 标准命名空间列表（只包含本 wiki 有的）。 */
    private static final List<Entry> MEDIAWIKI_NAMESPACES = List.of(
            new Entry(0, ""),
            new Entry(1, "讨论"),
            new Entry(2, "用户"),
            new Entry(3, "用户讨论"),
            new Entry(4, "WikiKernel"),
            new Entry(5, "WikiKernel讨论"),
            new Entry(6, "文件"),
            new Entry(7, "文件讨论"),
            new Entry(10, "模板"),
            new Entry(11, "模板讨论"),
            new Entry(12, "帮助"),
            new Entry(13, "帮助讨论"),
            new Entry(14, "分类"),
            new Entry(15, "分类讨论"),
            new Entry(274, "微件"),
            new Entry(275, "微件讨论"),
            new Entry(2300, "小部件"),
            new Entry(2301, "小部件讨论"),
            new Entry(828, "模块"),
            new Entry(829, "模块讨论"));

    private TransferNamespaces() {
    }

    /** 标题所属命名空间的 MediaWiki 编号（未识别按主命名空间 0）。 */
    public static int mediaWikiId(String title) {
        String value = title == null ? "" : title;
        WikiNamespaces.Namespace matched = null;
        int length = -1;
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            if (WikiNamespaces.MAIN.equals(namespace)) {
                continue;
            }
            for (String prefix : namespace.prefixes()) {
                if (value.regionMatches(true, 0, prefix, 0, prefix.length()) && prefix.length() > length) {
                    matched = namespace;
                    length = prefix.length();
                }
            }
        }
        if (matched == null) {
            return 0;
        }
        return switch (matched.id()) {
            case "talk" -> 1;
            case "user" -> 2;
            case "user-talk" -> 3;
            case "wikikernel" -> 4;
            case "wikikernel-talk" -> 5;
            case "file" -> 6;
            case "file-talk" -> 7;
            case "template" -> 10;
            case "template-talk" -> 11;
            case "help" -> 12;
            case "help-talk" -> 13;
            case "category" -> 14;
            case "category-talk" -> 15;
            case "widget" -> 274;
            case "widget-talk" -> 275;
            case "gadget" -> 2300;
            case "gadget-talk" -> 2301;
            case "module" -> 828;
            case "module-talk" -> 829;
            default -> 0;
        };
    }

    /** siteinfo 里的命名空间列表。 */
    public static List<Entry> siteinfo() {
        return MEDIAWIKI_NAMESPACES;
    }
}
