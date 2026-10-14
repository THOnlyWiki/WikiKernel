package cc.thonly.wikikernel.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 页面 URL 工具。
 * <p>
 * 标题里的 {@code /} 表示子页面（如 {@code 首页/样式.css}），必须保留为路径分隔符——
 * 不能用 {@code URLEncoder} 整串编码，否则 {@code /} 会变成 {@code %2F}，被 Tomcat 直接拒绝。
 * 这里按 {@code /} 拆段后逐段编码，得到浏览器与服务器都接受的路径。
 */
public final class WikiUrls {

    /** Wiki 页面路径前缀。 */
    public static final String WIKI_PREFIX = "/wiki/";

    private WikiUrls() {
    }

    /** 页面路径：{@code 首页/样式.css} → {@code /wiki/%E9%A6%96%E9%A1%B5/%E6%A0%B7%E5%BC%8F.css}。 */
    public static String pagePath(String title) {
        if (title == null || title.isEmpty()) {
            return WIKI_PREFIX;
        }
        return WIKI_PREFIX + Arrays.stream(title.split("/", -1))
                .map(WikiUrls::encodeSegment)
                .collect(Collectors.joining("/"));
    }

    /**
     * 页面名的 URL 编码（空格 → 下划线，保留子页面的 {@code /}）：
     * {@code A B/C D} → {@code A_B/C_D}。用于跨 Wiki 前缀的 {@code $1} 替换等需要「数据库键」形式的地方。
     */
    public static String encodeTitle(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }
        return Arrays.stream(title.replace(' ', '_').split("/", -1))
                .map(WikiUrls::encodeSegment)
                .collect(Collectors.joining("/"));
    }

    /**
     * 跨 Wiki 链接里页面名的编码（仿 MediaWiki 的 {@code wfUrlencode}）：
     * 空格 → 下划线，并把 MediaWiki 特意保留的 {@code : ' , $ @} 还原成字面字符，
     * 因此 {@code [[commons:File:Example.svg]]} 得到 {@code https://commons.wikimedia.org/wiki/File:Example.svg}。
     */
    public static String encodeInterwikiTitle(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }
        return Arrays.stream(title.replace(' ', '_').split("/", -1))
                .map(segment -> encodeSegment(segment)
                        .replace("%3A", ":").replace("%27", "'").replace("%2C", ",")
                        .replace("%24", "$").replace("%40", "@"))
                .collect(Collectors.joining("/"));
    }

    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
