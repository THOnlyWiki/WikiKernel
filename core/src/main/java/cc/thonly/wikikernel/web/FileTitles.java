package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.util.Strings;

/**
 * 文件命名空间标题工具（仿 MediaWiki 的 File: 命名空间）：
 * 文件页统一在 {@code 文件:<文件名>}（英文别名 {@code File:} / {@code Image:} 归一到中文前缀）。
 */
public final class FileTitles {

    /** 中文文件命名空间前缀（规范名）。 */
    public static final String PREFIX = "文件:";

    /** 英文别名前缀。 */
    public static final String PREFIX_ALIAS = "File:";

    /** MediaWiki 的历史别名前缀（{@code Image:} 与 {@code File:} 等价）。 */
    public static final String PREFIX_IMAGE_ALIAS = "Image:";

    private FileTitles() {
    }

    /** 是否是文件页（{@code 文件:X} / {@code File:X} / {@code Image:X}；{@code 文件讨论:X} 不算）。 */
    public static boolean isFileTitle(String title) {
        return fileNameOf(title) != null;
    }

    /** 标题对应的文件名；不是文件命名空间返回 null。 */
    public static String fileNameOf(String title) {
        String value = Strings.trimToEmpty(title);
        if (value.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
                || value.regionMatches(true, 0, PREFIX_ALIAS, 0, PREFIX_ALIAS.length())
                || value.regionMatches(true, 0, PREFIX_IMAGE_ALIAS, 0, PREFIX_IMAGE_ALIAS.length())) {
            int colon = value.indexOf(':');
            return value.substring(colon + 1).trim();
        }
        return null;
    }

    /** 文件名 → 规范文件页标题（{@code 文件:<名>}）。 */
    public static String titleOf(String fileName) {
        return PREFIX + Strings.trimToEmpty(fileName);
    }

    /**
     * 规范文件名（去掉首尾空白，{@code \} / {@code /} 替换为下划线，首字母大写——仿 MediaWiki 文件命名空间），
     * 与 FileService 落库规则一致。
     */
    public static String normalizeName(String name) {
        String value = Strings.trimToEmpty(name).replace('\\', '_').replace('/', '_');
        return WikiTitles.capitalizeFirstIfEnabled(value);
    }
}
