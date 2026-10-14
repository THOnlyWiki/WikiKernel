package cc.thonly.wikikernel.plugin.wikiseo.service;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.plugin.wikiseo.WikiSeoConfig;
import cc.thonly.wikikernel.plugin.wikiseo.model.SeoImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Head 阶段的 SEO 上下文：把「页面属性里的 SEO 参数 + 解析好的标题 / 描述 / 图片 / URL」
 * 打包给各 {@link SeoGenerator} 使用，并提供统一的 HTML 片段拼装与转义。
 * <p>
 * 图片解析规则（对应 WikiSEO 的 {@code AbstractBaseGenerator}）：
 * <ol>
 *     <li>有 {@code image} 参数：本地文件名用 {@link HeadContext#fileUrl}/{@code fileWidth}/{@code fileHeight}
 *     取绝对 URL 与真实尺寸；找不到文件时按 URL / 相对路径处理；</li>
 *     <li>没有 {@code image} 参数：用配置的 {@code default-image}；</li>
 *     <li>还没有：未关闭 {@code disable-logo-fallback-image} 时尝试站点 Logo 候选文件；</li>
 *     <li>都没有：不输出图片（WikiKernel 没有 {@code $wgLogo}，见 README）。</li>
 * </ol>
 */
public final class SeoHead {

    /** 手工描述标记的页面属性名（对应 MediaWiki 的 {@code manualDescription} page prop）。 */
    public static final String MANUAL_DESCRIPTION_PROPERTY = "manualDescription";

    /** 站点 Logo 兜底候选文件（WikiKernel 没有 $wgLogo，只能按常见文件名猜，见 README）。 */
    private static final List<String> LOGO_CANDIDATES =
            List.of("Logo.png", "Logo.jpg", "Logo.svg", "Wiki.png", "WikiLogo.png");

    private final HeadContext context;
    private final WikiSeoConfig config;
    private final Map<String, String> metadata;
    private final String description;
    private final String htmlTitle;
    private final String url;
    private final String updatedAt;
    private final SeoImage image;

    public SeoHead(HeadContext context, WikiSeoConfig config, Map<String, String> metadata, String description,
            String htmlTitle, String url, String updatedAt, SeoImage image) {
        this.context = context;
        this.config = config;
        // 保留插入顺序：hreflang 之类的输出顺序要稳定
        this.metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        this.description = description == null ? "" : description.trim();
        this.htmlTitle = htmlTitle == null ? "" : htmlTitle;
        this.url = url == null ? "" : url;
        this.updatedAt = updatedAt == null ? "" : updatedAt;
        this.image = image == null ? SeoImage.NONE : image;
    }

    // ==================== 生成器读取接口 ====================

    public HeadContext context() {
        return context;
    }

    public WikiSeoConfig config() {
        return config;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    /** 取参数值（没有返回空串）。 */
    public String get(String key) {
        return metadata.getOrDefault(key, "");
    }

    public boolean has(String key) {
        return !get(key).isEmpty();
    }

    public String pageTitle() {
        return context.pageTitle() == null ? "" : context.pageTitle();
    }

    /** 生效描述（页面手工描述，或自动生成的描述）。 */
    public String description() {
        return description;
    }

    /** 最终 {@code <title>}（没有 title 参数时为空串）。 */
    public String htmlTitle() {
        return htmlTitle;
    }

    /** 页面完整地址。 */
    public String url() {
        return url;
    }

    public String baseUrl() {
        return context.baseUrl() == null ? "" : context.baseUrl();
    }

    /** 页面最后更新时间（ISO-8601，未知为空串）。 */
    public String updatedAt() {
        return updatedAt;
    }

    public SeoImage image() {
        return image;
    }

    /** 站点名：页面 {@code site_name} 参数优先，其次 core 提供的站点名。 */
    public String siteName() {
        String metadataSiteName = get("site_name");
        if (!metadataSiteName.isEmpty()) {
            return metadataSiteName;
        }
        return context.siteName() == null ? "" : context.siteName();
    }

    /** 页面是否按 OpenGraph {@code article} 类型处理（未设置 type 时视为 article）。 */
    public boolean articleLike() {
        String type = get("type").trim().toLowerCase(Locale.ROOT);
        return type.isEmpty() || type.equals("article") || type.startsWith("article.");
    }

    /**
     * {@code article:modified_time} / JSON-LD {@code dateModified}：
     * 显式 {@code modified_time}（{@code -} 表示禁用）优先，否则用页面最后更新时间（仅 article 类型）。
     */
    public String modifiedTime() {
        String explicit = get("modified_time");
        if (!explicit.isEmpty()) {
            return "-".equals(explicit) ? "" : explicit;
        }
        return articleLike() ? updatedAt : "";
    }

    /** {@code article:published_time} / JSON-LD {@code datePublished}：显式值优先，其次同 modified_time。 */
    public String publishedTime() {
        String explicit = get("published_time");
        if (!explicit.isEmpty()) {
            return "-".equals(explicit) ? "" : explicit;
        }
        return modifiedTime();
    }

    /** {@code keywords} 按逗号拆分（对应 article:tag）。 */
    public List<String> keywords() {
        return split(get("keywords"), ",");
    }

    /** {@code author} 按分号拆分（对应 article:author）。 */
    public List<String> authors() {
        return split(get("author"), ";");
    }

    private static List<String> split(String value, String separator) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split(Pattern.quote(separator), -1)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    // ==================== HTML 片段拼装 ====================

    /** {@code <meta name="…" content="…"/>}。 */
    public String meta(String name, String content) {
        return "<meta name=\"" + escape(name) + "\" content=\"" + escape(content) + "\"/>";
    }

    /** {@code <meta property="…" content="…"/>}（OpenGraph / fb:app_id 用）。 */
    public String property(String property, String content) {
        return "<meta property=\"" + escape(property) + "\" content=\"" + escape(content) + "\"/>";
    }

    /** {@code <link rel="…" href="…"/>}。 */
    public String link(String rel, String href) {
        return "<link rel=\"" + escape(rel) + "\" href=\"" + escape(href) + "\"/>";
    }

    /** {@code <link rel="alternate" href="…" hreflang="…"/>}。 */
    public String alternateLink(String href, String hreflang) {
        return "<link rel=\"alternate\" href=\"" + escape(href) + "\" hreflang=\"" + escape(hreflang) + "\"/>";
    }

    /** {@code <script type="application/ld+json">…</script>}。 */
    public String jsonLd(String json) {
        return "<script type=\"application/ld+json\">" + json + "</script>";
    }

    /** HTML 属性值转义（防止注入）。 */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '\'' -> out.append("&#39;");
                default -> out.append(character);
            }
        }
        return out.toString();
    }

    // ==================== 图片解析 ====================

    /**
     * 解析页面/默认/Logo 图片。
     *
     * @param rawImage     页面 {@code image} 参数（可为空）
     * @param imageWidth   页面 {@code image_width} 参数
     * @param imageHeight 页面 {@code image_height} 参数
     */
    public static SeoImage resolveImage(HeadContext context, WikiSeoConfig config, String rawImage,
                                 String imageWidth, String imageHeight) {
        String candidate = rawImage == null ? "" : rawImage.trim();
        if (candidate.isEmpty()) {
            candidate = config.defaultImage();
        }
        if (candidate.isEmpty()) {
            if (config.disableLogoFallbackImage()) {
                return SeoImage.NONE;
            }
            for (String logo : LOGO_CANDIDATES) {
                if (!context.fileUrl(logo).isBlank()) {
                    return fromFile(context, logo);
                }
            }
            return SeoImage.NONE;
        }
        return resolve(context, candidate, imageWidth, imageHeight);
    }

    private static SeoImage resolve(HeadContext context, String value, String imageWidth, String imageHeight) {
        if (isAbsoluteUrl(value)) {
            return new SeoImage(value, intValue(imageWidth), intValue(imageHeight));
        }
        String fileUrl = context.fileUrl(value);
        if (fileUrl != null && !fileUrl.isBlank()) {
            // 本地文件：尺寸以文件真实大小为准（与 WikiSEO 的 getFileInfo 一致）
            return new SeoImage(absolutize(context, fileUrl),
                    context.fileWidth(value), context.fileHeight(value));
        }
        // 不是本地文件也不是绝对地址：按相对地址处理（相对原版更友好，见 README）
        return new SeoImage(absolutize(context, value), intValue(imageWidth), intValue(imageHeight));
    }

    private static SeoImage fromFile(HeadContext context, String fileName) {
        return new SeoImage(absolutize(context, context.fileUrl(fileName)),
                context.fileWidth(fileName), context.fileHeight(fileName));
    }

    private static boolean isAbsoluteUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || value.startsWith("//");
    }

    /** 相对地址补成绝对地址（协议相对地址补上站点协议）。 */
    private static String absolutize(HeadContext context, String url) {
        String base = context.baseUrl() == null ? "" : context.baseUrl();
        if (url.startsWith("//")) {
            int scheme = base.indexOf("://");
            return scheme > 0 ? base.substring(0, scheme + 1) + url : "https:" + url;
        }
        if (isAbsoluteUrl(url)) {
            return url;
        }
        if (url.startsWith("/")) {
            return base + url;
        }
        return base + "/" + url;
    }

    static int intValue(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
