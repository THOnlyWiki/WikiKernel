package cc.thonly.wikikernel.plugin.reverseproxy.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可选的 HTML / CSS URL 改写器。
 *
 * <p>只改写与上游同源的 URL（外部域名原样保留），不会把上游变成任意站点代理。
 * 复杂单页应用、WebSocket、由 JavaScript 动态拼接的 URL 不在处理范围内；这类
 * 站点应关闭改写，或者接受“部分资源仍直连上游”的限制。</p>
 */
@Component
public class ReverseProxyHtmlRewriter {

    private static final Set<String> HTML_URL_ATTRS = Set.of(
            "href", "src", "action", "formaction", "poster", "data");

    private static final Pattern CSS_URL = Pattern.compile(
            "url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CSS_IMPORT = Pattern.compile(
            "@import\\s+(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_REFRESH = Pattern.compile(
            "(?is)^(.*?url\\s*=\\s*)(['\"]?)([^'\";\\s]+)\\2(.*)$");

    public byte[] rewriteHtml(String slug, URI upstream, URI pageUri, byte[] body, String contentType) {
        String charsetName = charsetName(contentType);
        Charset charset;
        try {
            charset = Charset.forName(charsetName);
        } catch (RuntimeException exception) {
            charset = StandardCharsets.UTF_8;
            charsetName = charset.name();
        }
        try {
            Document document = Jsoup.parse(new ByteArrayInputStream(body), charsetName, pageUri.toString());
            rewriteDocument(slug, upstream, pageUri, document);
            document.outputSettings().charset(charset);
            return document.outerHtml().getBytes(charset);
        } catch (IOException | RuntimeException exception) {
            // 改写失败时宁可返回原内容，也不能让页面变成 500
            return body;
        }
    }

    public byte[] rewriteCss(String slug, URI upstream, URI pageUri, byte[] body, String contentType) {
        Charset charset;
        try {
            charset = Charset.forName(charsetName(contentType));
        } catch (RuntimeException exception) {
            charset = StandardCharsets.UTF_8;
        }
        try {
            String css = new String(body, charset);
            return rewriteCssText(slug, upstream, pageUri, css).getBytes(charset);
        } catch (RuntimeException exception) {
            return body;
        }
    }

    public String rewriteCssText(String slug, URI upstream, URI pageUri, String css) {
        if (css == null || css.isEmpty()) {
            return css;
        }
        String withUrls = replace(CSS_URL, css, matcher -> {
            String raw = matcher.group(2);
            String rewritten = rewrite(slug, upstream, pageUri, raw);
            return "url(" + matcher.group(1) + rewritten + matcher.group(1) + ")";
        });
        return replace(CSS_IMPORT, withUrls, matcher -> {
            String raw = matcher.group(2);
            return "@import " + matcher.group(1) + rewrite(slug, upstream, pageUri, raw) + matcher.group(1);
        });
    }

    private void rewriteDocument(String slug, URI upstream, URI pageUri, Document document) {
        for (Element element : document.getAllElements()) {
            for (String attribute : HTML_URL_ATTRS) {
                if (!element.hasAttr(attribute)) {
                    continue;
                }
                String oldValue = element.attr(attribute);
                if (oldValue.isBlank()) {
                    continue;
                }
                if ("data".equals(attribute) && !"object".equals(element.normalName())) {
                    continue;
                }
                String rewritten = rewrite(slug, upstream, pageUri, oldValue);
                if (!rewritten.equals(oldValue)) {
                    element.attr(attribute, rewritten);
                }
            }
            if (element.hasAttr("srcset")) {
                String oldValue = element.attr("srcset");
                element.attr("srcset", rewriteSrcset(slug, upstream, pageUri, oldValue));
            }
            if (element.hasAttr("style")) {
                String oldValue = element.attr("style");
                element.attr("style", rewriteCssText(slug, upstream, pageUri, oldValue));
            }
        }

        // <style> 块（style 属性已在上面处理）
        Elements styles = document.select("style");
        for (Element style : styles) {
            String css = style.data();
            if (!css.isEmpty()) {
                style.text(rewriteCssText(slug, upstream, pageUri, css));
            }
        }

        // <meta http-equiv="refresh" content="0; url=...">
        for (Element meta : document.select("meta[http-equiv]")) {
            if (!"refresh".equalsIgnoreCase(meta.attr("http-equiv")) || !meta.hasAttr("content")) {
                continue;
            }
            String content = meta.attr("content");
            Matcher matcher = META_REFRESH.matcher(content);
            if (matcher.matches()) {
                meta.attr("content", matcher.group(1) + matcher.group(2)
                        + rewrite(slug, upstream, pageUri, matcher.group(3))
                        + matcher.group(2) + matcher.group(4));
            }
        }
    }

    private String rewriteSrcset(String slug, URI upstream, URI pageUri, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        // data: URL 本身可以包含逗号；不要按 srcset 逗号拆分
        if (value.trim().toLowerCase(Locale.ROOT).startsWith("data:")) {
            return value;
        }
        StringBuilder out = new StringBuilder();
        for (String candidate : value.split(",")) {
            if (out.length() > 0) {
                out.append(", ");
            }
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int space = trimmed.indexOf(' ');
            if (space < 0) {
                out.append(rewrite(slug, upstream, pageUri, trimmed));
            } else {
                out.append(rewrite(slug, upstream, pageUri, trimmed.substring(0, space)))
                        .append(trimmed.substring(space));
            }
        }
        return out.toString();
    }

    private static String rewrite(String slug, URI upstream, URI pageUri, String raw) {
        return ReverseProxyUrlMapper.rewriteUrl(slug, upstream, pageUri, raw);
    }

    private static String replace(Pattern pattern, String input, Replacer replacer) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacer.replace(matcher)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String charsetName(String contentType) {
        if (contentType == null) {
            return "UTF-8";
        }
        int index = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (index < 0) {
            return "UTF-8";
        }
        String value = contentType.substring(index + "charset=".length()).trim();
        int end = value.indexOf(';');
        if (end >= 0) {
            value = value.substring(0, end).trim();
        }
        value = value.replace("\"", "").replace("'", "").trim();
        return value.isEmpty() ? "UTF-8" : value;
    }

    @FunctionalInterface
    private interface Replacer {
        String replace(Matcher matcher);
    }
}
