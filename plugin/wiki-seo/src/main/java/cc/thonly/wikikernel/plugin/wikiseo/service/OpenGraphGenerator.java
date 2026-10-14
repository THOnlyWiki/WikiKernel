package cc.thonly.wikikernel.plugin.wikiseo.service;

import cc.thonly.wikikernel.plugin.wikiseo.model.SeoImage;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenGraph 生成器（对应 WikiSEO 的 {@code Generator\Plugins\OpenGraph}）：
 * <ul>
 *     <li>{@code og:title}（页面标题）、{@code og:url}、{@code og:description}、{@code og:site_name}、{@code og:locale}、
 *     {@code og:type}；</li>
 *     <li>{@code og:image} / {@code og:image:width} / {@code og:image:height} / {@code og:image:alt}；</li>
 *     <li>article 类型补 {@code article:author}（多个用 {@code ;} 分隔）、{@code article:section}、
 *     {@code article:tag}（keywords 按逗号拆分）、{@code article:published_time}、{@code article:modified_time}；</li>
 *     <li>{@code modified_time=-} 可以关闭时间输出。</li>
 * </ul>
 */
public final class OpenGraphGenerator implements SeoGenerator {

    /** 生成器名。 */
    public static final String NAME = "OpenGraph";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> allowedParameters() {
        return List.of("author", "description", "image", "image_width", "image_height", "image_alt",
                "keywords", "locale", "modified_time", "published_time", "section", "site_name", "title", "type");
    }

    @Override
    public List<String> build(SeoHead head) {
        List<String> items = new ArrayList<>();

        String ogTitle = head.pageTitle().isEmpty() ? head.htmlTitle() : head.pageTitle();
        if (!ogTitle.isEmpty()) {
            items.add(head.property("og:title", ogTitle));
        }
        String siteName = head.siteName();
        if (!siteName.isEmpty()) {
            items.add(head.property("og:site_name", siteName));
        }
        if (!head.url().isEmpty()) {
            items.add(head.property("og:url", head.url()));
        }

        SeoImage image = head.image();
        if (image.present()) {
            items.add(head.property("og:image", image.url()));
            if (image.width() > 0) {
                items.add(head.property("og:image:width", Integer.toString(image.width())));
            }
            if (image.height() > 0) {
                items.add(head.property("og:image:height", Integer.toString(image.height())));
            }
            if (head.has("image_alt")) {
                items.add(head.property("og:image:alt", head.get("image_alt")));
            }
        }

        if (head.has("type")) {
            items.add(head.property("og:type", head.get("type")));
        }
        if (!head.description().isEmpty()) {
            items.add(head.property("og:description", head.description()));
        }
        if (head.has("locale")) {
            items.add(head.property("og:locale", head.get("locale")));
        }

        // OpenGraph type "article" 相关
        for (String author : head.authors()) {
            items.add(head.property("article:author", author));
        }
        if (head.has("section")) {
            items.add(head.property("article:section", head.get("section")));
        }
        for (String keyword : head.keywords()) {
            items.add(head.property("article:tag", keyword));
        }
        String publishedTime = head.publishedTime();
        if (!publishedTime.isEmpty()) {
            items.add(head.property("article:published_time", publishedTime));
        }
        String modifiedTime = head.modifiedTime();
        if (!modifiedTime.isEmpty()) {
            items.add(head.property("article:modified_time", modifiedTime));
        }
        return items;
    }
}
