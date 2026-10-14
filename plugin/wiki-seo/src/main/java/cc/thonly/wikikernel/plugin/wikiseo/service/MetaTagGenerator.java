package cc.thonly.wikikernel.plugin.wikiseo.service;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.plugin.wikiseo.WikiSeoConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基础元数据生成器（对应 WikiSEO 的 {@code Generator\MetaTag}，总是启用）：
 * <ul>
 *     <li>description / keywords / googlebot meta；</li>
 *     <li>robots：{@code robots} 参数按「索引策略,跟随策略」顺序输出；未设置时按「noindex 启发式」
 *     （页面标题命中 {@code noindex-page-titles}、或请求参数命中 {@code action=edit/history/…}、{@code diff}/{@code oldid} 等）；</li>
 *     <li>各站点验证码（Google / Bing / Yandex / Pinterest / Norton / Naver / Baidu）与
 *     {@code fb:app_id} / {@code fb:admins}（后两个用 {@code property=}）；</li>
 *     <li>hreflang：{@code hreflang_} 前缀参数 + 配置的默认语言（当前页）；</li>
 *     <li>canonical：{@code canonical} 参数覆盖；</li>
 *     <li>站点验证：Google、Bing、Yandex、Pinterest、Norton、Naver、Baidu、Facebook。</li>
 * </ul>
 */
public final class MetaTagGenerator implements SeoGenerator {

    /** 生成器名（MetaTag 不写进配置，总是启用）。 */
    public static final String NAME = "MetaTag";

    /** 命中这些请求参数（只判断键）就 noindex（对应 WikiSEO 的 $blockedURLParamKeys）。 */
    private static final List<String> BLOCKED_QUERY_KEYS = List.of(
            "curid", "diff", "from", "group", "mobileaction", "oldid",
            "printable", "profile", "redirect", "redlink", "stableid");

    /** 命中「键=值」组合就 noindex（对应 WikiSEO 的 $blockedURLParamKeyValuePairs）。 */
    private static final Map<String, List<String>> BLOCKED_QUERY_VALUES = Map.of(
            "action", List.of("delete", "edit", "history", "info", "pagevalues", "purge", "visualeditor", "watch"),
            "feed", List.of("rss"),
            "limit", List.of("500"),
            "veaction", List.of("edit", "editsource"));

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> build(SeoHead head) {
        List<String> items = new ArrayList<>();
        WikiSeoConfig config = head.config();

        addVerification(items, head, "google-site-verification", config.googleSiteVerificationKey());
        addVerification(items, head, "msvalidate.01", config.bingSiteVerificationKey());
        addVerification(items, head, "yandex-verification", config.yandexSiteVerificationKey());
        addVerification(items, head, "p:domain_verify", config.pinterestSiteVerificationKey());
        addVerification(items, head, "norton-safeweb-site-verification", config.nortonSiteVerificationKey());
        addVerification(items, head, "naver-site-verification", config.naverSiteVerificationKey());
        addVerification(items, head, "baidu-site-verification", config.baiduSiteVerificationKey());

        // Facebook 用 property= 而不是 name=（与 WikiSEO 的 Html::element meta property=… 一致）
        if (!config.facebookAppId().isEmpty()) {
            items.add(head.property("fb:app_id", config.facebookAppId()));
        }
        if (!config.facebookAdmins().isEmpty()) {
            items.add(head.property("fb:admins", config.facebookAdmins()));
        }

        addHrefLangs(items, head);
        addCanonical(items, head);
        addRobots(items, head);

        addMetaIfPresent(items, head, "description", head.description());
        addMetaIfPresent(items, head, "keywords", head.get("keywords"));
        addMetaIfPresent(items, head, "googlebot", head.get("googlebot"));
        return items;
    }

    private static void addVerification(List<String> items, SeoHead head, String name, String key) {
        if (key != null && !key.isEmpty()) {
            items.add(head.meta(name, key));
        }
    }

    private static void addMetaIfPresent(List<String> items, SeoHead head, String name, String value) {
        if (value != null && !value.isBlank()) {
            items.add(head.meta(name, value));
        }
    }

    /** hreflang：默认语言给当前页一条，然后按语言码顺序输出 {@code hreflang_xx} 参数。 */
    private static void addHrefLangs(List<String> items, SeoHead head) {
        String defaultLanguage = head.config().defaultLanguage();
        if (!defaultLanguage.isEmpty()) {
            items.add(head.alternateLink(head.url(), defaultLanguage));
        }
        List<String> keys = head.metadata().keySet().stream()
                .filter(SeoValidator::isHreflang)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        for (String key : keys) {
            items.add(head.alternateLink(head.metadata().get(key), SeoValidator.hreflangCode(key)));
        }
    }

    private static void addCanonical(List<String> items, SeoHead head) {
        String canonical = head.get("canonical");
        if (!canonical.isEmpty()) {
            items.add(head.link("canonical", canonical));
        }
    }

    /** robots：参数优先（索引策略,跟随策略），否则按启发式输出 noindex。 */
    private static void addRobots(List<String> items, SeoHead head) {
        String robots = head.get("robots");
        if (!robots.isEmpty()) {
            String[] parts = robots.split(",", -1);
            String indexPolicy = parts.length > 0 ? parts[0].trim() : "";
            String followPolicy = parts.length > 1 ? parts[1].trim() : "";
            String content = followPolicy.isEmpty() ? indexPolicy : indexPolicy + "," + followPolicy;
            if (!content.isEmpty()) {
                items.add(head.meta("robots", content));
            }
            return;
        }
        if (shouldNoIndex(head)) {
            items.add(head.meta("robots", "noindex"));
        }
    }

    /** 「noindex 启发式」（对应 WikiSEO 的 {@code MetaTag::shouldAddNoIndex()}）。 */
    static boolean shouldNoIndex(SeoHead head) {
        if (matchesNoindexTitle(head.context().pageTitle(), head.config().noindexPageTitles())) {
            return true;
        }
        Map<String, String> query = head.context().query();
        if (query == null || query.isEmpty()) {
            return false;
        }
        if (matchesNoindexTitle(query.get("title"), head.config().noindexPageTitles())) {
            return true;
        }
        for (Map.Entry<String, String> entry : query.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (BLOCKED_QUERY_KEYS.contains(key)) {
                return true;
            }
            List<String> blockedValues = BLOCKED_QUERY_VALUES.get(key);
            if (blockedValues != null && entry.getValue() != null && blockedValues.contains(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** 标题是否在 noindex 清单里（同时比较完整标题与去掉命名空间后的标题）。 */
    static boolean matchesNoindexTitle(String title, List<String> noindexTitles) {
        if (title == null || noindexTitles == null || noindexTitles.isEmpty()) {
            return false;
        }
        int colon = title.indexOf(':');
        String withoutNamespace = colon >= 0 ? title.substring(colon + 1) : title;
        for (String candidate : noindexTitles) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String trimmed = candidate.trim();
            if (trimmed.equals(title) || trimmed.equals(withoutNamespace)) {
                return true;
            }
        }
        return false;
    }
}
