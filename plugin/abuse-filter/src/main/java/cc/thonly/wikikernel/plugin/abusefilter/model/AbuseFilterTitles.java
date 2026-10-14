package cc.thonly.wikikernel.plugin.abusefilter.model;

import cc.thonly.wikikernel.common.util.Strings;

import java.util.List;

/**
 * 标题 → 过滤变量（{@code page_title} / {@code page_namespace}）的换算。
 * <p>
 * 从 core 的 {@code AbuseFilterServiceImpl} 原样移植（core 的 {@code WikiNamespaces} 是 core 内部类，
 * 插件不能引用）：{@code page_title} 是**去掉命名空间前缀**的标题，{@code page_namespace} 是
 * MediaWiki 风格的命名空间编号；未登记的命名空间按主命名空间 {@code 0} 处理（与迁移前一致）。
 */
public final class AbuseFilterTitles {

    /** 命名空间定义：前缀列表 + MediaWiki 编号。 */
    private record Namespace(List<String> prefixes, long id) {
    }

    private static final List<Namespace> NAMESPACES = List.of(
            new Namespace(List.of("讨论:", "Talk:"), 1),
            new Namespace(List.of("用户:", "User:"), 2),
            new Namespace(List.of("用户讨论:", "User talk:"), 3),
            new Namespace(List.of("WikiKernel:"), 4),
            new Namespace(List.of("WikiKernel讨论:", "WikiKernel talk:"), 5),
            new Namespace(List.of("文件:", "File:"), 6),
            new Namespace(List.of("文件讨论:", "File talk:"), 7),
            new Namespace(List.of("模板:", "Template:"), 10),
            new Namespace(List.of("模板讨论:", "Template talk:"), 11),
            new Namespace(List.of("帮助:", "Help:"), 12),
            new Namespace(List.of("帮助讨论:", "Help talk:"), 13),
            new Namespace(List.of("分类:", "Category:"), 14),
            new Namespace(List.of("分类讨论:", "Category talk:"), 15),
            new Namespace(List.of("微件:", "Widget:"), 2300),
            new Namespace(List.of("微件讨论:", "Widget talk:"), 2301));

    private AbuseFilterTitles() {
    }

    /** 页面标题（不含命名空间前缀，仿 MediaWiki 的 {@code page_title}）。 */
    public static String titleOnly(String title) {
        String value = Strings.trimToEmpty(title);
        for (Namespace namespace : NAMESPACES) {
            for (String prefix : namespace.prefixes()) {
                if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return value.substring(prefix.length()).trim();
                }
            }
        }
        return value;
    }

    /** 命名空间编号（仿 MediaWiki 的 {@code page_namespace}；未知命名空间按主命名空间 0 处理）。 */
    public static long namespaceId(String title) {
        String value = Strings.trimToEmpty(title);
        for (Namespace namespace : NAMESPACES) {
            for (String prefix : namespace.prefixes()) {
                if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return namespace.id();
                }
            }
        }
        return 0;
    }
}
