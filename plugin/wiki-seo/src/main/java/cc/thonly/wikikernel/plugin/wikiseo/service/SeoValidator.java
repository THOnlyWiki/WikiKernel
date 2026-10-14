package cc.thonly.wikikernel.plugin.wikiseo.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 参数校验（对应 MediaWiki WikiSEO 的 {@code Validator}）：
 * <ul>
 *     <li>基础参数来自 WikiSEO 的 {@code Validator::$validParameterNames}；</li>
 *     <li>各元数据生成器通过 {@link SeoGenerator#allowedParameters()} 追加自己的参数
 *     （对应 WikiSEO 的 {@code mergeValidParameterNames()}）；</li>
 *     <li>{@code hreflang_} 前缀参数按前缀接受（原版还内置了 ISO 语言码表逐项校验，见 README）。</li>
 * </ul>
 * 不在合法集合里的参数一律忽略（与 {@code Validator::validateParams} 一致）。
 */
public final class SeoValidator {

    /** hreflang 参数前缀（原版的正则位是 {@code hreflang_%}）。 */
    public static final String HREFLANG_PREFIX = "hreflang_";

    /**
     * 基础参数（对应 Validator::$validParameterNames 去掉 hreflang 前缀项）。
     * <p>
     * 说明：原版把 {@code twitter_card} 放在 Twitter 生成器里（未启用 Twitter 时该参数会被忽略）；
     * WikiKernel 把它放进基础集合，配置里没启用 Twitter 时参数仍会保存，但不会产生输出。
     */
    private static final List<String> BASE_PARAMS = List.of(
            "title",
            "title_mode",
            "title_separator",

            "keywords",
            "description",
            "canonical",

            "robots",
            "googlebot",

            "image", "image_width", "image_height", "image_alt",

            "type",
            "site_name",
            "locale",
            "section",
            "author",

            "published_time",

            "twitter_site",
            "twitter_card");

    private final Set<String> params;

    public SeoValidator(Collection<String> generatorParams) {
        Set<String> all = new TreeSet<>(BASE_PARAMS);
        if (generatorParams != null) {
            for (String param : generatorParams) {
                if (param != null && !param.isBlank()) {
                    all.add(param.trim());
                }
            }
        }
        this.params = Set.copyOf(all);
    }

    /** 按生成器集合构造（MetaTag 总是启用，其参数已包含在基础集合里）。 */
    public static SeoValidator forGenerators(Collection<SeoGenerator> generators) {
        Set<String> extra = new TreeSet<>();
        if (generators != null) {
            for (SeoGenerator generator : generators) {
                extra.addAll(generator.allowedParameters());
            }
        }
        return new SeoValidator(extra);
    }

    /** 参数名是否合法。 */
    public boolean valid(String key) {
        return key != null && params.contains(key);
    }

    /** 全部合法参数名（用于文档 / 调试）。 */
    public Set<String> validParams() {
        return params;
    }

    /** 是否是 {@code hreflang_} 前缀参数。 */
    public static boolean isHreflang(String key) {
        return key != null && key.length() > HREFLANG_PREFIX.length()
                && key.regionMatches(true, 0, HREFLANG_PREFIX, 0, HREFLANG_PREFIX.length());
    }

    /** 取 hreflang 语言码（{@code hreflang_de-de} → {@code de-de}）；不是 hreflang 参数返回空串。 */
    public static String hreflangCode(String key) {
        return isHreflang(key) ? key.substring(HREFLANG_PREFIX.length()) : "";
    }

    /** 过滤掉未知参数与空值（对应 {@code Validator::validateParams} + WikiSEO 的 {@code !empty()} 过滤）。 */
    public Map<String, String> validate(Map<String, String> raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (valid(key) || isHreflang(key)) {
                result.put(key, value.trim());
            }
        }
        return result;
    }

    /** 从页面属性里挑出 SEO 参数（对应 {@code WikiSEO::loadPagePropsFromDb()}）。 */
    public Map<String, String> seoProperties(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return Map.of();
        }
        return validate(properties);
    }
}
