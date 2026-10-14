package cc.thonly.wikikernel.plugin.wikiseo.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.wikiseo.WikiSeoConfig;
import cc.thonly.wikikernel.plugin.wikiseo.model.SeoImage;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoAutoDescription;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoGenerator;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoHead;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoMetadata;
import cc.thonly.wikikernel.plugin.wikiseo.service.SeoValidator;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@link HtmlHeadProvider} 实现（对应 WikiSEO 的 {@code PageHooks::onBeforePageDisplay} +
 * {@code WikiSEO::addMetadataToPage}）：按页面属性里的 SEO 参数生成 {@code <meta>} / {@code <link>} /
 * {@code application/ld+json} 片段，并按 {@code title_mode} 覆盖 {@code <title>}。
 * <p>
 * 页面属性来自 core 的页面属性表（渲染时由 {@link SeoParserFunction} / {@link SeoTag} 写入并持久化，
 * 相当于 MediaWiki 的 page_props）。没有手工描述时，若开启自动描述则用页面正文现场生成。
 */
public final class SeoHeadProvider implements HtmlHeadProvider {

    /** 执行顺序（值越小越先执行；core 目前只有本插件提供 Head 片段）。 */
    private static final int ORDER = 50;

    private final WikiSeoConfig config;
    private final SeoValidator validator;
    private final List<SeoGenerator> generators;
    private final WikiPageStore pageStore;

    /**
     * @param config     插件配置
     * @param validator  参数校验器（含生成器参数）
     * @param generators 生效的生成器（已含 MetaTag）
     * @param pageStore  页面访问桥（自动描述 / 更新时间用；可为 null）
     */
    public SeoHeadProvider(WikiSeoConfig config, SeoValidator validator,
                           List<SeoGenerator> generators, WikiPageStore pageStore) {
        this.config = config;
        this.validator = validator;
        this.generators = List.copyOf(generators);
        this.pageStore = pageStore;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        Map<String, String> properties = validator.seoProperties(context.properties());
        String description = description(context, properties);
        String htmlTitle = htmlTitle(context);
        SeoImage image = SeoHead.resolveImage(context, config,
                properties.get("image"), properties.get("image_width"), properties.get("image_height"));
        SeoHead head = new SeoHead(context, config, properties, description, htmlTitle,
                context.pageUrl(), updatedAt(context), image);

        List<String> items = new ArrayList<>();
        for (SeoGenerator generator : generators) {
            List<String> produced = generator.build(head);
            if (produced != null) {
                items.addAll(produced);
            }
        }
        return items;
    }

    /**
     * 覆盖 {@code <title>}（对应 WikiSEO 的 {@code modifyPageTitle()}）：
     * 只有设置了 {@code title} 参数才覆盖；{@code title_mode} 支持 append / prepend / replace（默认）。
     */
    @Override
    public String htmlTitle(HeadContext context) {
        Map<String, String> properties = validator.seoProperties(context.properties());
        String title = properties.getOrDefault("title", "");
        if (title.isBlank()) {
            return null;
        }
        String separator = properties.getOrDefault("title_separator", " - ");
        if (separator.isBlank()) {
            separator = " - ";
        }
        String pageTitle = SeoMetadata.sanitize(context.pageTitle());
        String result = switch (properties.getOrDefault("title_mode", "replace").trim().toLowerCase(Locale.ROOT)) {
            case "append" -> pageTitle + separator + title;
            case "prepend" -> title + separator + pageTitle;
            default -> title;
        };
        // 与 MediaWiki 一致：标题里不允许换行，最后再做一次实体解码
        result = result.replace("\r", "").replace("\n", "");
        result = SeoMetadata.decodeEntities(result);
        return result.isBlank() ? null : result;
    }

    /** 手工描述优先；否则按配置用页面正文生成自动描述。 */
    private String description(HeadContext context, Map<String, String> properties) {
        String description = properties.getOrDefault("description", "").trim();
        if (!description.isEmpty() && !SeoAutoDescription.isAutoSource(description)) {
            return description;
        }
        if (!config.enableAutoDescription() || pageStore == null || context.pageId() <= 0) {
            return "";
        }
        String content = pageStore.content(context.pageId()).orElse("");
        return SeoAutoDescription.from(content, config.tryCleanAutoDescription());
    }

    /** 页面最后更新时间（ISO-8601；WikiKernel 是本地时间，没有时区信息）。 */
    private String updatedAt(HeadContext context) {
        if (pageStore == null || context.pageId() <= 0) {
            return "";
        }
        return pageStore.byId(context.pageId())
                .map(PageSnapshot::updatedAt)
                .filter(Objects::nonNull)
                .map(time -> time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .orElse("");
    }
}
