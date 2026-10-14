package cc.thonly.wikikernel.i18n;

import cc.thonly.wikikernel.common.util.Strings;
import org.springframework.stereotype.Component;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ISO 3166-1 地区（249 个，{@link Locale#getISOCountries()}）的本地化名称与代码校验。
 * <p>
 * 名称直接取 JDK 自带的 CLDR 数据（{@code Locale.of("", code).getDisplayCountry(locale)}）：
 * 249 个地区在中文与英文下都有名字、不会退化成代码，所以不用为每个地区各写一条文案
 * （也没有把 498 条地区名塞进 {@code messages*.properties}）。
 * <p>
 * 只有需要换写法的地区用 {@code country.<代码>} 消息键覆盖（当前是 TW / HK / MO）；
 * 缺键时回退 JDK 名称，所以删掉键也不会露出英文名或空白。
 */
@Component
public class CountryNames {

    /** 用消息键覆盖名称的地区（键 {@code country.<代码>}）。 */
    private static final List<String> OVERRIDE_CODES = List.of("TW", "HK", "MO");

    /** 全部合法地区代码（ISO 3166-1 alpha-2，大写；「未指定」不是代码，用空串表示）。 */
    private static final Set<String> CODES = Set.of(Locale.getISOCountries());

    private final PluginMessageSource messageSource;

    public CountryNames(PluginMessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /** 规范化成大写代码；空串（未指定）与非法值都返回空串。 */
    public static String normalize(String code) {
        String value = Strings.trimToEmpty(code).toUpperCase(Locale.ROOT);
        return CODES.contains(value) ? value : "";
    }

    /** 地区的本地化名称；代码非法（含未指定）时返回空串。 */
    public String displayName(String code, Locale locale) {
        String value = normalize(code);
        if (value.isEmpty()) {
            return "";
        }
        Locale effective = locale == null ? Locale.getDefault() : locale;
        if (OVERRIDE_CODES.contains(value)) {
            String override = messageSource.resolveOrNull("country." + value, effective);
            if (!Strings.isBlank(override)) {
                return override;
            }
        }
        return Locale.of("", value).getDisplayCountry(effective);
    }

    /**
     * 全部地区：代码 + 当前语言下的名称，按名称排序（中文按拼音、英文按字母；
     * 名称相同时按代码排序，保证顺序稳定）。
     */
    public List<LocalizedName> localizedNames(Locale locale) {
        Locale effective = locale == null ? Locale.getDefault() : locale;
        List<LocalizedName> entries = new ArrayList<>();
        for (String code : CODES) {
            entries.add(new LocalizedName(code, displayName(code, effective)));
        }
        Collator collator = Collator.getInstance(effective);
        entries.sort(Comparator.comparing(LocalizedName::name, collator)
                .thenComparing(LocalizedName::code));
        return entries;
    }

    /** 地区代码 + 本地化名称（下拉框选项用）。 */
    public record LocalizedName(String code, String name) {
    }
}
