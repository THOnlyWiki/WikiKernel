package cc.thonly.wikikernel.plugin.wikiseo.service;

import cc.thonly.wikikernel.plugin.wikiseo.model.SeoImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema.org 生成器（对应 WikiSEO 的 {@code Generator\Plugins\SchemaOrg}）：
 * 输出一段 {@code application/ld+json}，含 {@code @type}（默认 {@code Article}）、headline、description、
 * url、image、author / publisher、datePublished / dateModified、keywords 与
 * {@code potentialAction}（站内搜索，对应原版基于 Special:Search 的 SearchAction）。
 */
public final class SchemaOrgGenerator implements SeoGenerator {

    /** 生成器名。 */
    public static final String NAME = "SchemaOrg";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> allowedParameters() {
        return List.of("type", "description", "keywords", "modified_time", "published_time", "section");
    }

    @Override
    public List<String> build(SeoHead head) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("@context", "https://schema.org");
        json.put("@type", head.has("type") ? head.get("type") : "Article");

        String title = head.htmlTitle().isEmpty() ? head.pageTitle() : head.htmlTitle();
        if (!title.isEmpty()) {
            json.put("name", title);
            json.put("headline", title);
        }
        if (!head.pageTitle().isEmpty()) {
            json.put("mainEntityOfPage", head.pageTitle());
        }
        if (!head.url().isEmpty()) {
            json.put("identifier", head.url());
            json.put("url", head.url());
        }
        if (!head.description().isEmpty()) {
            json.put("description", head.description());
        }
        if (head.has("keywords")) {
            json.put("keywords", head.get("keywords"));
        }
        if (head.has("section")) {
            json.put("articleSection", head.get("section"));
        }
        String publishedTime = head.publishedTime();
        if (!publishedTime.isEmpty()) {
            json.put("datePublished", publishedTime);
        }
        String modifiedTime = head.modifiedTime();
        if (!modifiedTime.isEmpty()) {
            json.put("dateModified", modifiedTime);
        }

        SeoImage image = head.image();
        if (image.present()) {
            Map<String, Object> imageObject = new LinkedHashMap<>();
            imageObject.put("@type", "ImageObject");
            imageObject.put("url", image.url());
            if (image.width() > 0) {
                imageObject.put("width", image.width());
            }
            if (image.height() > 0) {
                imageObject.put("height", image.height());
            }
            json.put("image", imageObject);
        }

        Map<String, Object> organization = new LinkedHashMap<>();
        organization.put("@type", "Organization");
        organization.put("name", head.siteName());
        if (!head.baseUrl().isEmpty()) {
            organization.put("url", head.baseUrl());
        }
        json.put("author", organization);
        json.put("publisher", organization);
        json.put("potentialAction", searchAction(head));

        return List.of(head.jsonLd(SeoJson.write(json)));
    }

    /** 站内搜索（对应原版 {@code getSearchActionMetadata()}，WikiKernel 的搜索页是 {@code 特殊:搜索?q=}）。 */
    private static Map<String, Object> searchAction(SeoHead head) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("@type", "SearchAction");
        action.put("target", head.baseUrl() + "/wiki/Special:Search?q={search_term}");
        action.put("query-input", "required name=search_term");
        return action;
    }
}
