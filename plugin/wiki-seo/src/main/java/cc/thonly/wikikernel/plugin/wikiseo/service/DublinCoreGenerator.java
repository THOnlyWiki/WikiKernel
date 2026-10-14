package cc.thonly.wikikernel.plugin.wikiseo.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Dublin Core 生成器（对应 WikiSEO 的 {@code Generator\Plugins\DublinCore}）：
 * 输出 {@code DC.title} / {@code DC.description} / {@code DC.creator} / {@code DC.language} /
 * {@code DC.publisher} / {@code DC.identifier}（页面地址）与 {@code dc.identifier.wikidata}。
 */
public final class DublinCoreGenerator implements SeoGenerator {

    /** 生成器名。 */
    public static final String NAME = "DublinCore";

    /** Dublin Core 与 MediaWiki 的 schema.DC 声明。 */
    private static final String SCHEMA_LINK = "<link rel=\"schema.DC\" href=\"http://purl.org/dc/clients/1.1/\"/>";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> allowedParameters() {
        return List.of("author", "description", "dc.identifier.wikidata", "locale", "site_name", "title");
    }

    @Override
    public List<String> build(SeoHead head) {
        List<String> items = new ArrayList<>();
        items.add(SCHEMA_LINK);
        addIfPresent(items, head, "DC.title", head.get("title"));
        addIfPresent(items, head, "DC.description", head.description());
        addIfPresent(items, head, "DC.creator", head.get("author"));
        addIfPresent(items, head, "DC.language", head.get("locale"));
        addIfPresent(items, head, "DC.publisher", head.siteName());
        addIfPresent(items, head, "DC.identifier", head.url());
        addIfPresent(items, head, "dc.identifier.wikidata", head.get("dc.identifier.wikidata"));
        return items;
    }

    private static void addIfPresent(List<String> items, SeoHead head, String name, String value) {
        if (value != null && !value.isBlank()) {
            items.add(head.meta(name, value));
        }
    }
}
