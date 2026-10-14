package cc.thonly.wikikernel.actionapi;

import java.util.List;
import java.util.Locale;

/**
 * MediaWiki API 命名空间映射（id ↔ 前缀），与 {@code transfer.TransferNamespaces} 的编号保持一致。
 */
public final class ActionApiNamespaces {

    /** 命名空间：MediaWiki 编号 + 规范前缀（主命名空间为空串）。 */
    public record Ns(int id, String name) {
    }

    private static final List<Ns> LIST = List.of(
            new Ns(0, ""),
            new Ns(1, "讨论"),
            new Ns(2, "用户"),
            new Ns(3, "用户讨论"),
            new Ns(4, "WikiKernel"),
            new Ns(5, "WikiKernel讨论"),
            new Ns(6, "文件"),
            new Ns(7, "文件讨论"),
            new Ns(10, "模板"),
            new Ns(11, "模板讨论"),
            new Ns(12, "帮助"),
            new Ns(13, "帮助讨论"),
            new Ns(14, "分类"),
            new Ns(15, "分类讨论"),
            new Ns(274, "微件"),
            new Ns(275, "微件讨论"),
            new Ns(2300, "小部件"),
            new Ns(2301, "小部件讨论"),
            new Ns(828, "模块"),
            new Ns(829, "模块讨论"));

    private ActionApiNamespaces() {
    }

    public static List<Ns> all() {
        return LIST;
    }

    /** 标题的命名空间编号（最长前缀优先；未识别按主命名空间 0）。 */
    public static int idOf(String title) {
        String value = title == null ? "" : title;
        int best = 0;
        int length = -1;
        for (Ns ns : LIST) {
            if (ns.id() == 0) {
                continue;
            }
            String prefix = ns.name() + ":";
            if (value.regionMatches(true, 0, prefix, 0, prefix.length()) && prefix.length() > length) {
                best = ns.id();
                length = prefix.length();
            }
        }
        return best;
    }

    /** 标题是否属于命名空间 {@code nsId}（{@code nsId < 0} = 不过滤）。 */
    public static boolean matches(String title, int nsId) {
        if (nsId < 0) {
            return true;
        }
        return idOf(title) == nsId;
    }

    /** 去掉命名空间前缀后的标题（主命名空间原样）。 */
    public static String bareTitle(String title) {
        String value = title == null ? "" : title;
        for (Ns ns : LIST) {
            if (ns.id() == 0) {
                continue;
            }
            String prefix = ns.name() + ":";
            if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return value.substring(prefix.length());
            }
        }
        return value;
    }

    /** 带规范前缀的完整标题。 */
    public static String fullTitle(int nsId, String bare) {
        for (Ns ns : LIST) {
            if (ns.id() == nsId) {
                return ns.id() == 0 ? bare : ns.name() + ":" + bare;
            }
        }
        return bare;
    }

    /** 命名空间展示名（siteinfo 用；主命名空间为空串）。 */
    public static String nameOf(int nsId) {
        return LIST.stream().filter(ns -> ns.id() == nsId).map(Ns::name).findFirst().orElse("");
    }

    /** 大小写规则（本 wiki 统一 first-letter）。 */
    public static String caseOf(int nsId) {
        return "first-letter";
    }

    /** 某个命名空间的本地化名称（英文界面返回英文名，其余用中文规范名）。 */
    public static String displayName(int nsId, Locale locale) {
        String chinese = nameOf(nsId);
        if (chinese.isEmpty() || locale == null || "zh".equals(locale.getLanguage())) {
            return chinese;
        }
        return switch (nsId) {
            case 1 -> "Talk";
            case 2 -> "User";
            case 3 -> "User talk";
            case 4 -> "WikiKernel";
            case 5 -> "WikiKernel talk";
            case 6 -> "File";
            case 7 -> "File talk";
            case 10 -> "Template";
            case 11 -> "Template talk";
            case 12 -> "Help";
            case 13 -> "Help talk";
            case 14 -> "Category";
            case 15 -> "Category talk";
            case 274 -> "Widget";
            case 275 -> "Widget talk";
            case 2300 -> "Gadget";
            case 2301 -> "Gadget talk";
            case 828 -> "Module";
            case 829 -> "Module talk";
            default -> chinese;
        };
    }
}
