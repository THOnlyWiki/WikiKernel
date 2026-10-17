package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.util.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 命名空间定义（仿 MediaWiki 的命名空间）：稳定 id + 规范前缀（主命名空间为空）+ 本地化消息键；
 * 英文别名前缀（{@code User:} / {@code Category:} 等）一并归入对应命名空间。
 * <p>
 * 别名与规范前缀属于<b>同一个命名空间</b>：{@link #canonicalize(String)} 把别名 / 大小写变体统一成
 * 每个命名空间的第一个前缀（规范名），唯一调用入口是 {@link WikiTitles#normalize(String)}。
 * <p>
 * 目前用于 {@code 特殊:全部页面} 的命名空间筛选；新增命名空间时在这里登记即可。
 */
public final class WikiNamespaces {

    /** 命名空间定义：id（稳定，URL 参数用）+ 显示名消息键 + 前缀列表（含英文别名；主命名空间为空）。 */
    public record Namespace(String id, String labelKey, List<String> prefixes) {
    }

    /** 主命名空间：不带任何命名空间前缀。 */
    public static final Namespace MAIN = new Namespace("main", "namespace.main", List.of());

    private static final List<Namespace> NAMESPACES = List.of(
            MAIN,
            new Namespace("talk", "namespace.talk", List.of("讨论:", "Talk:")),
            new Namespace("user", "namespace.user", List.of("用户:", "User:")),
            new Namespace("user-talk", "namespace.userTalk", List.of("用户讨论:", "User talk:")),
            new Namespace("category", "namespace.category", List.of("分类:", "Category:")),
            new Namespace("category-talk", "namespace.categoryTalk", List.of("分类讨论:", "Category talk:")),
            new Namespace("template", "namespace.template", List.of("模板:", "Template:")),
            new Namespace("template-talk", "namespace.templateTalk", List.of("模板讨论:", "Template talk:")),
            new Namespace("file", "namespace.file", List.of("文件:", "File:", "Image:")),
            new Namespace("file-talk", "namespace.fileTalk", List.of("文件讨论:", "File talk:")),
            new Namespace("help", "namespace.help", List.of("帮助:", "Help:")),
            new Namespace("help-talk", "namespace.helpTalk", List.of("帮助讨论:", "Help talk:")),
            new Namespace("widget", "namespace.widget", List.of("微件:", "Widget:")),
            new Namespace("widget-talk", "namespace.widgetTalk", List.of("微件讨论:", "Widget talk:")),
            new Namespace("module", "namespace.module", List.of("模块:", "Module:")),
            new Namespace("module-talk", "namespace.moduleTalk", List.of("模块讨论:", "Module talk:")),
            new Namespace("graph", "namespace.graph", List.of("图表:", "Graph:")),
            new Namespace("graph-talk", "namespace.graphTalk", List.of("图表讨论:", "Graph talk:")),
            new Namespace("component", "namespace.component", List.of("组件:", "Component:")),
            new Namespace("component-talk", "namespace.componentTalk", List.of("组件讨论:", "Component talk:")),
            new Namespace("gadget", "namespace.gadget", List.of("小部件:", "Gadget:")),
            new Namespace("gadget-talk", "namespace.gadgetTalk", List.of("小部件讨论:", "Gadget talk:")),
            new Namespace("wikikernel", "namespace.wikikernel", List.of("WikiKernel:")),
            new Namespace("wikikernel-talk", "namespace.wikikernelTalk",
                    List.of("WikiKernel讨论:", "WikiKernel talk:")),
            new Namespace("mediawiki", "namespace.mediawiki", List.of("MediaWiki:")),
            new Namespace("mediawiki-talk", "namespace.mediawikiTalk",
                    List.of("MediaWiki talk:", "MediaWiki讨论:")));

    /**
     * 特殊页前缀（规范名在前）：特殊页没有页面行，因此不进 {@link #NAMESPACES}（否则命名空间编号 /
     * 命名空间列表会与主命名空间冲突），只参与 {@link #canonicalize(String)} 的别名归一。
     */
    private static final List<String> SPECIAL_PREFIXES = List.of("特殊:", "Special:");

    /** 前缀组（每组第一个是规范前缀）：内容命名空间 + 特殊页，供别名归一 / 别名列举使用。 */
    private static final List<List<String>> PREFIX_GROUPS = prefixGroups();

    private WikiNamespaces() {
    }

    /** 组装 {@link #PREFIX_GROUPS}：主命名空间没有任何前缀，不参与。 */
    private static List<List<String>> prefixGroups() {
        List<List<String>> groups = new ArrayList<>();
        for (Namespace namespace : NAMESPACES) {
            if (!namespace.prefixes().isEmpty()) {
                groups.add(namespace.prefixes());
            }
        }
        groups.add(SPECIAL_PREFIXES);
        return List.copyOf(groups);
    }

    /** 全部命名空间（含主命名空间）。 */
    public static List<Namespace> all() {
        return NAMESPACES;
    }

    /**
     * 把标题开头的命名空间**别名**（不区分大小写）替换成**规范（本地化）前缀**：命名空间别名的唯一归一入口，
     * 由 {@link WikiTitles#normalize(String)} 调用（全站按标题的查询 / 保存 / 校验都经过它）。
     * <p>
     * 例如 {@code User:甲 → 用户:甲}、{@code template:乙 → 模板:乙}、{@code IMAGE:丙.png → 文件:丙.png}、
     * {@code Special:版本 → 特殊:版本}：MediaWiki 语义里它们与规范名是同一个页面，
     * 不能因为链接 / URL 写了英文别名就落到另一个标题上。
     * <p>
     * <b>只改前缀</b>：前缀以外的部分原样返回（空白折叠、首字母大写仍由 {@link WikiTitles#normalize(String)} 负责）；
     * 不含命名空间前缀（如 {@code time: now}）或只有前缀本身（如 {@code 模板:}）时原样返回。
     */
    public static String canonicalize(String title) {
        String value = Strings.trimToEmpty(title);
        PrefixMatch match = matchPrefix(value);
        return match == null ? value : match.canonical() + value.substring(match.length());
    }

    /**
     * 规范标题的其它别名写法（历史数据回退用）：别名归一之前按英文别名落库的页面
     * （如 {@code Gadget:X}）在规范名下查不到时，用这些写法再查一次。
     * <p>
     * {@code 小部件:X → [Gadget:X]}、{@code 用户:X → [User:X]}、{@code 文件:X → [File:X, Image:X]}；
     * 主命名空间 / 未命中命名空间 → 空列表。
     */
    public static List<String> aliasVariants(String title) {
        String value = Strings.trimToEmpty(title);
        PrefixMatch match = matchPrefix(value);
        if (match == null) {
            return List.of();
        }
        String rest = value.substring(match.length());
        List<String> variants = new ArrayList<>();
        for (List<String> prefixes : PREFIX_GROUPS) {
            if (!prefixes.get(0).equals(match.canonical())) {
                continue;
            }
            for (int i = 1; i < prefixes.size(); i++) {
                variants.add(prefixes.get(i) + rest);
            }
        }
        return List.copyOf(variants);
    }

    /** 前缀命中结果：规范前缀 + 命中的前缀长度。 */
    private record PrefixMatch(String canonical, int length) {
    }

    /**
     * 匹配标题开头的命名空间前缀（不区分大小写）：多个命中时取<b>最长</b>者；
     * 前缀后面必须有内容（只有前缀本身的标题交给「非法标题」校验处理）；没有命中返回 {@code null}。
     */
    private static PrefixMatch matchPrefix(String value) {
        PrefixMatch match = null;
        for (List<String> prefixes : PREFIX_GROUPS) {
            for (String prefix : prefixes) {
                if (value.length() > prefix.length()
                        && value.regionMatches(true, 0, prefix, 0, prefix.length())
                        && (match == null || prefix.length() > match.length())) {
                    match = new PrefixMatch(prefixes.get(0), prefix.length());
                }
            }
        }
        return match;
    }

    /** 按稳定 id 查命名空间（{@code all} / 空 / 未知 → 空 Optional，表示不筛选）。 */
    public static Optional<Namespace> byId(String id) {
        String value = Strings.trimToEmpty(id);
        if (value.isEmpty() || "all".equalsIgnoreCase(value)) {
            return Optional.empty();
        }
        return NAMESPACES.stream()
                .filter(namespace -> namespace.id().equalsIgnoreCase(value))
                .findFirst();
    }

    /** 标题是否属于该命名空间（{@code null} = 不筛选，全部通过；主命名空间 = 不带任何前缀）。 */
    public static boolean matches(String title, Namespace namespace) {
        String value = Strings.trimToEmpty(title);
        if (namespace == null) {
            return true;
        }
        if (MAIN.equals(namespace)) {
            return !inAnyNamespace(value);
        }
        for (String prefix : namespace.prefixes()) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }

    /** 标题是否带任一命名空间前缀（含英文别名）。 */
    public static boolean inAnyNamespace(String title) {
        String value = Strings.trimToEmpty(title);
        for (Namespace namespace : NAMESPACES) {
            if (MAIN.equals(namespace)) {
                continue;
            }
            for (String prefix : namespace.prefixes()) {
                if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 是否属于微件（Widget）命名空间正文页（不含讨论页）。 */
    public static boolean isWidget(String title) {
        return byId("widget").map(namespace -> matches(title, namespace)).orElse(false);
    }

    /** 是否属于 MediaWiki Gadgets 命名空间正文页。 */
    public static boolean isGadget(String title) {
        return byId("gadget").map(namespace -> matches(title, namespace)).orElse(false);
    }

    /** 是否属于 Lua 模块命名空间正文页（{@code 模块:} / {@code Module:}）。 */
    public static boolean isModule(String title) {
        return byId("module").map(namespace -> matches(title, namespace)).orElse(false);
    }

    /** 是否属于 Graph 命名空间正文页（{@code 图表:} / {@code Graph:}）：整页渲染成 Vega 图形。 */
    public static boolean isGraph(String title) {
        return byId("graph").map(namespace -> matches(title, namespace)).orElse(false);
    }

    /** 是否属于 WikiKernel（界面）命名空间正文页（不含讨论页）。 */
    public static boolean isWikiKernel(String title) {
        return byId("wikikernel").map(namespace -> matches(title, namespace)).orElse(false);
    }

    /** 是否属于用户命名空间正文页（{@code 用户:X} / {@code User:X}，不含用户讨论页）。 */
    public static boolean isUser(String title) {
        return byId("user").map(namespace -> matches(title, namespace)).orElse(false);
    }

    /**
     * 用户页标题（{@code 用户:X} / {@code User:X}）→ 用户名 {@code X}；
     * 不是用户命名空间返回 {@code null}（讨论页 / 其它命名空间一律为 null）。
     */
    public static String userPageName(String title) {
        String value = Strings.trimToEmpty(title);
        Optional<Namespace> user = byId("user");
        if (user.isEmpty() || !isUser(value)) {
            return null;
        }
        for (String prefix : user.get().prefixes()) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return value.substring(prefix.length()).trim();
            }
        }
        return null;
    }
}
