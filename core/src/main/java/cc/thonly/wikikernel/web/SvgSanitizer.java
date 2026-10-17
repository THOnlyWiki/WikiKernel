package cc.thonly.wikikernel.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SVG 上传清洗：只保留静态图形需要的元素 / 属性，移除脚本、事件处理器、foreignObject、
 * 动画与不安全的外链，避免同源 SVG 被直接打开时执行脚本（存储型 XSS）。
 * <p>
 * 采用白名单而不是黑名单：未知元素 / 属性一律丢弃；无法解析或根节点不是 {@code <svg>} 时拒绝上传。
 * 仍然建议在 {@code /files/**} 响应上加 CSP sandbox（见 {@code FileController}）作为第二道防线。
 */
public final class SvgSanitizer {

    private SvgSanitizer() {
    }

    /** 清洗失败（不是合法 SVG / 含 DTD / 根节点不是 svg）。 */
    public static final class InvalidSvgException extends RuntimeException {
        public InvalidSvgException(String message) {
            super(message);
        }

        public InvalidSvgException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 允许保留的 SVG 元素（静态图形、文本、渐变、裁剪、常见滤镜）。 */
    private static final Set<String> ALLOWED_ELEMENTS = Set.of(
            "svg", "g", "defs", "symbol", "use", "a",
            "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
            "text", "tspan", "textpath", "title", "desc", "style",
            "lineargradient", "radialgradient", "stop", "clippath", "mask", "pattern",
            "filter", "fegaussianblur", "feoffset", "feblend", "fecolormatrix",
            "femerge", "femergenode", "feflood", "fecomposite", "marker",
            "fedropshadow", "feturbulence", "fedisplacementmap", "fecomponenttransfer",
            "fefunca", "fefuncb", "fefuncg", "fefuncr", "fespecularlighting",
            "fediffuselighting", "fepointlight", "fedistantlight", "fespotlight",
            "image");

    /** 允许保留的属性（不含任何 on* / 事件属性）。 */
    private static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
            "id", "class", "style", "transform",
            "d", "x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry",
            "width", "height", "viewbox", "preserveaspectratio", "points", "pathlength",
            "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width",
            "stroke-linecap", "stroke-linejoin", "stroke-miterlimit",
            "stroke-dasharray", "stroke-dashoffset", "stroke-opacity",
            "opacity", "stop-color", "stop-opacity", "offset",
            "gradientunits", "gradienttransform", "spreadmethod",
            "patternunits", "patterncontentunits", "patterntransform",
            "clippathunits", "maskunits", "maskcontentunits",
            "filterunits", "primitiveunits", "stddeviation", "in", "in2", "result",
            "marker-start", "marker-mid", "marker-end", "markerwidth", "markerheight",
            "markerunits", "refx", "refy", "orient",
            "clip-path", "clip-rule", "mask", "filter",
            "font-family", "font-size", "font-weight", "font-style", "font-variant",
            "text-anchor", "dominant-baseline", "alignment-baseline", "baseline-shift",
            "letter-spacing", "word-spacing", "dx", "dy", "rotate",
            "textlength", "lengthadjust", "href", "xlink:href",
            "xmlns", "xmlns:xlink", "version", "baseprofile", "xml:space", "xml:lang",
            "role", "aria-label", "aria-hidden", "aria-labelledby",
            "focusable", "overflow", "color", "color-interpolation",
            "color-interpolation-filters", "display", "visibility", "vector-effect",
            "paint-order", "shape-rendering", "enable-background", "isolation",
            "mix-blend-mode");

    private static final Pattern URL_IN_CSS = Pattern.compile("url\\(\\s*([^)]*?)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 清洗 SVG 字节内容，返回可安全存储 / 内联展示的 UTF-8 SVG。
     *
     * @throws InvalidSvgException 不是 SVG、含 DTD / 实体声明或解析失败
     */
    public static byte[] sanitize(byte[] content) {
        if (content == null || content.length == 0) {
            throw new InvalidSvgException("empty svg");
        }
        String text = new String(content, StandardCharsets.UTF_8).replace("\uFEFF", "");
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("<!doctype") || lower.contains("<!entity")) {
            throw new InvalidSvgException("DTD / entity is not allowed");
        }
        Document document;
        try {
            document = Jsoup.parse(text, "", Parser.xmlParser());
        } catch (RuntimeException exception) {
            throw new InvalidSvgException("invalid svg xml", exception);
        }
        Element root = document.children().isEmpty() ? null : document.children().first();
        if (root == null || !"svg".equalsIgnoreCase(root.tagName())) {
            throw new InvalidSvgException("root element must be svg");
        }

        // 注释 / 处理指令（<?xml …?>）不进入输出
        for (Node node : new ArrayList<>(document.childNodes())) {
            if (!(node instanceof Element)) {
                node.remove();
            }
        }

        for (Element element : new ArrayList<>(root.getAllElements())) {
            if (element == root) {
                sanitizeAttributes(root);
                continue;
            }
            String tag = element.tagName().toLowerCase(Locale.ROOT);
            if (!ALLOWED_ELEMENTS.contains(tag)) {
                element.remove();
                continue;
            }
            sanitizeAttributes(element);
            if ("style".equals(tag)) {
                String css = element.wholeText().toLowerCase(Locale.ROOT);
                if (unsafeCss(css)) {
                    element.remove();
                }
            }
        }

        // 统一成标准 SVG 命名空间，避免用错误命名空间伪装成 SVG
        root.attr("xmlns", "http://www.w3.org/2000/svg");
        document.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .prettyPrint(false);
        return root.outerHtml().getBytes(StandardCharsets.UTF_8);
    }

    private static void sanitizeAttributes(Element element) {
        String tag = element.tagName().toLowerCase(Locale.ROOT);
        for (Attribute attribute : new ArrayList<>(element.attributes().asList())) {
            String name = attribute.getKey().toLowerCase(Locale.ROOT);
            String value = attribute.getValue();
            boolean allowed = ALLOWED_ATTRIBUTES.contains(name)
                    || name.startsWith("aria-") || name.startsWith("data-");
            if (name.startsWith("on") || !allowed) {
                element.removeAttr(attribute.getKey());
                continue;
            }
            if (("href".equals(name) || "xlink:href".equals(name)) && !safeHref(tag, value)) {
                element.removeAttr(attribute.getKey());
                continue;
            }
            if ("style".equals(name) && unsafeCss(value)) {
                element.removeAttr(attribute.getKey());
                continue;
            }
            String normalized = value.toLowerCase(Locale.ROOT)
                    .replaceAll("[\\s\\u0000-\\u001F]", "");
            if (normalized.contains("javascript:")) {
                element.removeAttr(attribute.getKey());
            }
        }
    }

    /**
     * 外链策略：{@code use} 只允许同文档 {@code #id}；{@code image}/{@code a} 允许 data:image、相对路径或 http(s)。
     * 不允许协议相对 {@code //host}（避免外链嗅探），也不允许 javascript:。
     */
    private static boolean safeHref(String tag, String value) {
        String href = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\u0000-\\u001F]", "");
        if (href.isEmpty()) {
            return true;
        }
        if (href.startsWith("#")) {
            return true;
        }
        if (href.startsWith("javascript:") || href.startsWith("data:text/html")
                || href.startsWith("data:image/svg")) {
            return false;
        }
        if ("use".equals(tag)) {
            return false;
        }
        if (href.startsWith("data:image/") || href.startsWith("/") || href.startsWith("./")
                || href.startsWith("../")) {
            return true;
        }
        return href.startsWith("http://") || href.startsWith("https://");
    }

    private static boolean unsafeCss(String css) {
        String value = css == null ? "" : css.toLowerCase(Locale.ROOT).replace("\\", "");
        if (value.contains("javascript:") || value.contains("expression(")
                || value.contains("behavior:") || value.contains("@import")) {
            return true;
        }
        Matcher matcher = URL_IN_CSS.matcher(value);
        while (matcher.find()) {
            String target = matcher.group(1).trim().replace("\"", "").replace("'", "");
            if (!(target.startsWith("data:image/") || target.startsWith("#")
                    || target.startsWith("/") || target.startsWith("./") || target.startsWith("../")
                    || target.startsWith("http://") || target.startsWith("https://"))) {
                return true;
            }
        }
        return false;
    }
}
