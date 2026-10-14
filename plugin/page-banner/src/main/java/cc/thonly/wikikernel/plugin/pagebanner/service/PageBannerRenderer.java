package cc.thonly.wikikernel.plugin.pagebanner.service;

import cc.thonly.wikikernel.common.service.FileStore;
import cc.thonly.wikikernel.common.service.StoredMediaFile;
import cc.thonly.wikikernel.plugin.pagebanner.PageBannerSettings;
import cc.thonly.wikikernel.plugin.pagebanner.hook.PageBannerDirective;
import cc.thonly.wikikernel.plugin.pagebanner.hook.PageBannerHook;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.util.HtmlUtils;

/**
 * 页面横幅 HTML 生成（迁移自 core 的 {@code PageBannerServiceImpl.htmlFor}，并改成**多图轮播**）：
 * <ul>
 *     <li>开关 {@code features.page-banner} 关闭、或页面被 {@code <exclude-banner-page />} 排除时不显示；</li>
 *     <li>白名单模式开启时只有白名单页面（或逐页配置里出现过的页面）显示横幅；</li>
 *     <li>图片顺序：{@code {{PAGEBANNER:文件名}}}（可写多个，用 {@code |} 分隔）&gt; 逐页配置里的多个文件名
 *     &gt; 图片池（按 {@code features.page-banner.files} 的书写顺序，轮换机制决定**从哪一张开始**）；</li>
 *     <li>每一张都必须是在站内已存在的**图片**文件（{@link FileStore}），不存在的直接跳过；
 *     一张都不剩时不显示横幅；</li>
 *     <li>多张：带控件的轮播容器（自动翻页 + 上一张 / 下一张 + 指示点 + 键盘方向键），前端脚本
 *     {@code /plugins/page-banner/page-banner.js} 负责翻页；</li>
 *     <li>一张：退化成静态横幅（没有控件标记，脚本不会启用轮播）；无 JS 时第一张照常显示
 *     （所有图片默认可见，只有脚本启动后才隐藏非当前张，见 page-banner.css）。</li>
 * </ul>
 * 输出结构（{@code data-*} 属性把轮播配置与本地化文案交给脚本，脚本里不写死任何文案）：
 * <pre>
 * &lt;div class="page-banner" data-page-banner-carousel data-slide-count="2"
 *      data-interval="5" data-transition="fade" data-controls="true" data-pause-on-hover="true"
 *      data-prev-label="…" data-next-label="…" data-goto-label="…" data-status-label="…"&gt;
 *   &lt;div class="page-banner-viewport"&gt;&lt;div class="page-banner-track"&gt;
 *     &lt;div class="page-banner-slide page-banner-slide--active" data-slide-index="0"
 *          aria-hidden="false"&gt;&lt;img …&gt;[&lt;div class="page-banner-caption"&gt;…&lt;/div&gt;]&lt;/div&gt;
 *     …
 *   &lt;/div&gt;&lt;/div&gt;
 *   [&lt;div class="page-banner-progress"&gt;&lt;span class="page-banner-progress-bar"&gt;&lt;/span&gt;&lt;/div&gt;]
 *   [&lt;div class="page-banner-controls"&gt;按钮 + 指示点&lt;/div&gt;]
 * &lt;/div&gt;
 * </pre>
 */
public final class PageBannerRenderer {

    /** 页面属性键：{@code {{PAGEBANNER:文件名}}} 指定的横幅文件（由 {@link PageBannerDirective} 写入）。 */
    public static final String FILE_PROPERTY = "pagebanner-file";
    /** 页面属性键：{@code caption=说明}（由 {@link PageBannerDirective} 写入）。 */
    public static final String CAPTION_PROPERTY = "pagebanner-caption";
    /** 页面属性键：{@code <exclude-banner-page />} 让本页不显示横幅（值 {@code 1}）。 */
    public static final String EXCLUDE_PROPERTY = "pagebanner-exclude";

    /** 横幅样式（插件静态资源；{@code #page-banner} 容器与它的外边距由 core 皮肤 / base.css 提供）。 */
    public static final String STYLESHEET = "/plugins/page-banner/page-banner.css";
    /** 横幅轮播脚本（插件静态资源；只在多张图片时注入，{@code defer} 加载）。 */
    public static final String SCRIPT = "/plugins/page-banner/page-banner.js";

    /** 轮播容器的 CSS 类 / 标记属性（脚本据此启用轮播）。 */
    public static final String CAROUSEL_CLASS = "page-banner-carousel";
    /** 轮播容器上的标记属性（{@code data-page-banner-carousel}，脚本的选择器）。 */
    public static final String CAROUSEL_ATTRIBUTE = "data-page-banner-carousel";
    /** 有翻页控件时容器上的 CSS 类（说明文字据此抬到控件上方，不依赖 {@code :has()}）。 */
    public static final String CONTROLS_CLASS = "page-banner--controls";
    /** 指示点的 CSS 类（脚本与样式共用）。 */
    public static final String DOT_CLASS = "page-banner-dot";

    /** 文案键：上一张（按钮的 {@code aria-label} / {@code title}）。 */
    public static final String PREV_LABEL_KEY = "pageBanner.prev";
    /** 文案键：下一张。 */
    public static final String NEXT_LABEL_KEY = "pageBanner.next";
    /** 文案键：跳到第 N 张（指示点的 {@code aria-label}，{@code {0}} = 序号）。 */
    public static final String GOTO_LABEL_KEY = "pageBanner.goto";
    /** 文案键：第 N / 共 M 张（轮播容器的 {@code aria-label}）。 */
    public static final String STATUS_LABEL_KEY = "pageBanner.status";
    /** 文案键：横幅图片区域（容器没有站点名时用）。 */
    public static final String REGION_LABEL_KEY = "pageBanner.region";

    /** 轮播最多渲染的图片数（图片池写得很长时的兜底，避免一页塞进几十张图）。 */
    public static final int MAX_SLIDES = 24;

    private final PageBannerSettings settings;
    private final FileStore fileStore;

    /** {@code rotate} 模式的轮换计数器（进程内，重启归零；与迁移前的 core 行为一致）。 */
    private final AtomicLong rotationCounter = new AtomicLong();

    /**
     * 上一次 {@link #slidesFor} 的结果缓存（键 + 值一起换，避免读到别人的结果）：
     * 同一次页面渲染里 {@code beforeTitle}（正文）与 {@code headItems}（是否注入轮播脚本）都要这份列表，
     * 缓存避免把 {@link FileStore#find} 的查询做两遍；{@code random} / {@code rotate} 模式下也保证
     * 正文与 head 看到的是同一批图片。
     */
    private String cacheKey;
    private List<StoredMediaFile> cachedSlides = List.of();

    public PageBannerRenderer(PageBannerSettings settings, FileStore fileStore) {
        this.settings = settings;
        this.fileStore = fileStore;
    }

    /**
     * 本页是否**可能**显示横幅：开关 / 排除标记 / 白名单与逐页配置的判定（不含「图片是否真的存在」）。
     * 供注入样式与脚本（{@code headItems}）用：只做便宜的设置判定，不查数据库。
     */
    public boolean mayDisplay(String pageTitle, boolean excluded) {
        if (pageTitle == null || excluded || !settings.enabled()) {
            return false;
        }
        if (!settings.whitelistEnabled()) {
            return true;
        }
        String title = PageBannerSettings.normalize(pageTitle);
        return settings.whitelist().contains(pageTitle)
                || settings.whitelist().contains(title)
                || settings.perPage().containsKey(title);
    }

    /** 轮播配置（间隔 / 过渡 / 控件 / 悬停暂停，见 {@link PageBannerSettings#carousel()}）。 */
    public PageBannerSettings.CarouselOptions carouselOptions() {
        return settings.carousel();
    }

    /**
     * 计算页面的横幅 HTML。
     *
     * @param pageTitle     页面标题（白名单 / 逐页配置比对与轮换起始位置用）
     * @param requestedFile {@code {{PAGEBANNER:文件名}}} 指定的图片（可空；多个文件名用 {@code |} 分隔）
     * @param caption       {@code caption=} 说明文字（可空）
     * @param excluded      是否带 {@code <exclude-banner-page />}
     * @return 横幅 HTML（不显示时返回 {@code null}）
     */
    public String htmlFor(String pageTitle, String requestedFile, String caption, boolean excluded) {
        return htmlFor(pageTitle, requestedFile, caption, excluded, TextProvider.KEYS);
    }

    /**
     * 计算页面的横幅 HTML，控件文案由 {@code texts} 提供（{@link PageBannerHook} 传本地化文案；
     * 取不到时用**翻译键本身**，绝不写死中文）。
     */
    public String htmlFor(String pageTitle, String requestedFile, String caption, boolean excluded,
                          TextProvider texts) {
        List<StoredMediaFile> slides = slidesFor(pageTitle, requestedFile, excluded);
        return slides.isEmpty() ? null : html(slides, caption, texts == null ? TextProvider.KEYS : texts);
    }

    /** 从页面属性里取指令参数并生成横幅 HTML（{@link PageBannerHook} 用）。 */
    public String htmlForProperties(String pageTitle, Map<String, String> properties) {
        return htmlForProperties(pageTitle, properties, TextProvider.KEYS);
    }

    /** 从页面属性里取指令参数并生成横幅 HTML（文案由 {@code texts} 提供）。 */
    public String htmlForProperties(String pageTitle, Map<String, String> properties, TextProvider texts) {
        Map<String, String> values = properties == null ? Map.of() : properties;
        return htmlFor(pageTitle, values.get(FILE_PROPERTY), values.get(CAPTION_PROPERTY),
                excluded(values), texts);
    }

    /**
     * 本页会显示的全部横幅图片（顺序稳定；不显示时返回空列表）。
     * 供 {@link PageBannerHook} 判断「是否需要注入轮播脚本」用（与 {@link #htmlForProperties} 同一份判定）。
     */
    public List<StoredMediaFile> slidesForProperties(String pageTitle, Map<String, String> properties) {
        String requestedFile = properties == null ? null : properties.get(FILE_PROPERTY);
        return slidesFor(pageTitle, requestedFile, excluded(properties));
    }

    /** 本页会显示的全部横幅图片（顺序稳定；不显示时返回空列表）。 */
    private List<StoredMediaFile> slidesFor(String pageTitle, String requestedFile, boolean excluded) {
        String key = (pageTitle == null ? "" : pageTitle) + '\u0000'
                + (requestedFile == null ? "" : requestedFile) + '\u0000' + excluded;
        String cachedKey;
        List<StoredMediaFile> cached;
        synchronized (this) {
            cachedKey = cacheKey;
            cached = cachedSlides;
        }
        if (key.equals(cachedKey)) {
            return cached;
        }
        List<StoredMediaFile> slides = computeSlides(pageTitle, requestedFile, excluded);
        synchronized (this) {
            cacheKey = key;
            cachedSlides = slides;
        }
        return slides;
    }

    /** 实际计算：设置判定 → 文件名顺序（指令 &gt; 逐页配置 &gt; 图片池）→ 存在性过滤。 */
    private List<StoredMediaFile> computeSlides(String pageTitle, String requestedFile, boolean excluded) {
        if (!mayDisplay(pageTitle, excluded)) {
            return List.of();
        }
        String title = PageBannerSettings.normalize(pageTitle);
        List<String> names;
        List<String> requested = splitList(requestedFile);
        if (!requested.isEmpty()) {
            // {{PAGEBANNER:甲|乙}}：本页轮播指定的这几张（额外参数如 caption= 不在文件名里）
            names = requested;
        } else {
            List<String> mapped = splitList(settings.perPage().getOrDefault(title, ""));
            names = mapped.isEmpty() ? orderedPool(pageTitle) : mapped;
        }
        return lookup(names);
    }

    /**
     * 图片池的轮播顺序：从轮换机制选中的那一张开始，按图片池的书写顺序绕一圈
     * （{@code stable} / {@code daily} / {@code hourly} 固定，{@code random} / {@code rotate} 每次浏览换一个起点）。
     */
    private List<String> orderedPool(String pageTitle) {
        List<String> pool = settings.files();
        if (pool.size() <= 1) {
            return pool;
        }
        String first = settings.pick(pageTitle, rotationCounter::getAndIncrement);
        int start = pool.indexOf(first);
        if (start <= 0) {
            return pool;
        }
        List<String> ordered = new ArrayList<>(pool.size());
        ordered.addAll(pool.subList(start, pool.size()));
        ordered.addAll(pool.subList(0, start));
        return List.copyOf(ordered);
    }

    /** 文件名 → 已存在的图片文件（保持顺序、去重、跳过缺失与非图片、最多 {@link #MAX_SLIDES} 张）。 */
    private List<StoredMediaFile> lookup(List<String> names) {
        if (names.isEmpty() || fileStore == null) {
            return List.of();
        }
        List<StoredMediaFile> files = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            if (files.size() >= MAX_SLIDES) {
                break;
            }
            String fileName = name == null ? "" : name.trim();
            if (fileName.isEmpty() || !seen.add(fileName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Optional<StoredMediaFile> file = fileStore.find(fileName);
            if (file.isPresent() && file.get().image()) {
                files.add(file.get());
            }
        }
        return List.copyOf(files);
    }

    /** 页面属性里是否带 {@code <exclude-banner-page />} 的排除标记。 */
    public static boolean excluded(Map<String, String> properties) {
        return properties != null && "1".equals(properties.get(EXCLUDE_PROPERTY));
    }

    /** 横幅 HTML：一张 = 静态图片；多张 = 轮播容器（脚本启动后才隐藏非当前张）。 */
    private String html(List<StoredMediaFile> slides, String caption, TextProvider texts) {
        String text = caption == null ? "" : caption.trim();
        boolean carousel = slides.size() > 1;
        PageBannerSettings.CarouselOptions options = carousel ? carouselOptions() : null;
        // 只有多张图片且 controls 为真时才输出翻页控件（一张图片没有任何控件）
        boolean withControls = carousel && options.controls();
        StringBuilder html = new StringBuilder(256);
        html.append("<div class=\"page-banner");
        if (carousel) {
            html.append(' ').append(CAROUSEL_CLASS);
        }
        if (withControls) {
            html.append(' ').append(CONTROLS_CLASS);
        }
        html.append('"');
        if (carousel) {
            html.append(' ').append(CAROUSEL_ATTRIBUTE)
                    .append(" data-slide-count=\"").append(slides.size()).append('"')
                    .append(" data-interval=\"").append(Math.max(0, options.intervalSeconds())).append('"')
                    .append(" data-transition=\"").append(attribute(options.transition())).append('"')
                    .append(" data-controls=\"").append(options.controls()).append('"')
                    .append(" data-pause-on-hover=\"").append(options.pauseOnHover()).append('"')
                    .append(" role=\"group\" aria-roledescription=\"carousel\"")
                    .append(" aria-label=\"").append(attribute(texts.text(STATUS_LABEL_KEY))).append('"')
                    .append(" data-prev-label=\"").append(attribute(texts.text(PREV_LABEL_KEY))).append('"')
                    .append(" data-next-label=\"").append(attribute(texts.text(NEXT_LABEL_KEY))).append('"')
                    .append(" data-goto-label=\"").append(attribute(texts.text(GOTO_LABEL_KEY))).append('"')
                    .append(" data-status-label=\"").append(attribute(texts.text(STATUS_LABEL_KEY))).append('"');
        }
        html.append(" aria-label=\"").append(attribute(texts.text(REGION_LABEL_KEY))).append("\">")
                .append("<div class=\"page-banner-viewport\"><div class=\"page-banner-track\">");
        for (int index = 0; index < slides.size(); index++) {
            slide(html, slides.get(index), text, index, index == 0);
        }
        html.append("</div></div>");
        if (carousel) {
            html.append("<div class=\"page-banner-progress\"><span class=\"page-banner-progress-bar\"></span></div>");
            if (withControls) {
                controls(html, slides.size(), texts);
            }
        }
        html.append("</div>");
        return html.toString();
    }

    /** 一张图片（含叠加在图片下缘的说明文字；首张默认当前，无 JS 时也显示它）。 */
    private static void slide(StringBuilder html, StoredMediaFile file, String captionText,
                              int index, boolean active) {
        html.append("<div class=\"page-banner-slide")
                .append(active ? " page-banner-slide--active" : "")
                .append("\" data-slide-index=\"").append(index)
                .append("\" aria-hidden=\"").append(active ? "false" : "true").append("\">")
                .append("<img src=\"")
                .append(HtmlUtils.htmlEscape(file.url() == null ? "" : file.url()))
                .append("\" alt=\"")
                .append(HtmlUtils.htmlEscape(captionText.isEmpty()
                        ? (file.name() == null ? "" : file.name()) : ""))
                .append("\" loading=\"lazy\"/>");
        if (!captionText.isEmpty()) {
            html.append("<div class=\"page-banner-caption\">")
                    .append(HtmlUtils.htmlEscape(captionText))
                    .append("</div>");
        }
        html.append("</div>");
    }

    /** 翻页控件：上一张 / 下一张 + 指示点（文案来自 {@code texts}，只有多张时才有）。 */
    private static void controls(StringBuilder html, int count, TextProvider texts) {
        String prev = attribute(texts.text(PREV_LABEL_KEY));
        String next = attribute(texts.text(NEXT_LABEL_KEY));
        html.append("<div class=\"page-banner-controls\">")
                .append("<button type=\"button\" class=\"page-banner-prev\" aria-label=\"").append(prev)
                .append("\" title=\"").append(prev).append("\"></button>")
                .append("<span class=\"page-banner-dots\">");
        for (int index = 0; index < count; index++) {
            html.append("<button type=\"button\" class=\"").append(DOT_CLASS)
                    .append(index == 0 ? " page-banner-dot--active" : "")
                    .append("\" data-slide-to=\"").append(index)
                    .append("\" aria-current=\"").append(index == 0 ? "true" : "false")
                    .append("\" aria-label=\"")
                    .append(attribute(texts.text(GOTO_LABEL_KEY).replace("{0}", String.valueOf(index + 1))))
                    .append("\"></button>");
        }
        html.append("</span><button type=\"button\" class=\"page-banner-next\" aria-label=\"").append(next)
                .append("\" title=\"").append(next).append("\"></button>")
                .append("</div>");
    }

    /** HTML 属性值转义（{@code "} / {@code &} / {@code <} 都要转义，否则会撑破属性）。 */
    private static String attribute(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    /** {@code |} / 换行分隔的文件名 → 列表（顺序保留，去空白与空项）。 */
    private static List<String> splitList(String value) {
        String source = value == null ? "" : value;
        List<String> items = new ArrayList<>();
        for (String item : source.split("[|\\r\\n]+")) {
            String text = item.trim();
            if (!text.isEmpty()) {
                items.add(text);
            }
        }
        return List.copyOf(items);
    }

    /**
     * 控件文案来源：{@link TextProvider#KEYS}（返回翻译键本身，缺键时一眼可见）或本地化实现
     * （见 {@code PageBannerHook} 的 {@code window.WIKI_MESSAGES} 注入）。
     */
    @FunctionalInterface
    public interface TextProvider {

        /** 原样返回翻译键：core 的消息源缺键时显示键名，这里保持同一策略（不写死中文）。 */
        TextProvider KEYS = key -> key;

        /** 取一条文案；找不到时返回翻译键本身。 */
        String text(String key);
    }
}
