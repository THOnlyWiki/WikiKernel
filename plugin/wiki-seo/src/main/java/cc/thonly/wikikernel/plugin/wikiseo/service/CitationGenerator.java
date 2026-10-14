package cc.thonly.wikikernel.plugin.wikiseo.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Citation 生成器（对应 WikiSEO 的 {@code Generator\Plugins\Citation}）：输出 {@code citation_*} meta，
 * {@code citation_author} 按 {@code ;} 拆成多条。
 * <p>
 * 与 MediaWiki 原版的差异：原版还会额外输出一段 {@code jsonld-metadata-citation}，
 * 这里只保留 meta（见 README）。
 */
public final class CitationGenerator implements SeoGenerator {

    /** 生成器名。 */
    public static final String NAME = "Citation";

    /** Citation 参数（与 WikiSEO 的 Citation::$tags 一致，description 只参与校验不输出）。 */
    private static final List<String> PARAMS = List.of(
            "description",
            "citation_author",
            "citation_abstract_html_url",
            "citation_conference_title",
            "citation_creation_date",
            "citation_doi",
            "citation_firstpage",
            "citation_headline",
            "citation_isbn",
            "citation_issn",
            "citation_issue",
            "citation_journal_title",
            "citation_keywords",
            "citation_lastpage",
            "citation_license",
            "citation_name",
            "citation_pdf_url",
            "citation_publication_date",
            "citation_publisher",
            "citation_title",
            "citation_type",
            "citation_volume");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> allowedParameters() {
        return PARAMS;
    }

    @Override
    public List<String> build(SeoHead head) {
        List<String> items = new ArrayList<>();
        for (String param : PARAMS) {
            if (param.equals("description")) {
                // 与 WikiSEO 的 Citation::addMetaTags 一致：description 不输出 citation_* meta
                continue;
            }
            String value = head.get(param);
            if (value.isEmpty()) {
                continue;
            }
            if (param.equals("citation_author")) {
                for (String author : value.split(";", -1)) {
                    String trimmed = author.trim();
                    if (!trimmed.isEmpty()) {
                        items.add(head.meta(param, trimmed));
                    }
                }
            } else {
                items.add(head.meta(param, value));
            }
        }
        return items;
    }
}
