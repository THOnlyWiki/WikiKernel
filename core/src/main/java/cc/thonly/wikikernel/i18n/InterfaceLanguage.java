package cc.thonly.wikikernel.i18n;

import java.util.Locale;

public enum InterfaceLanguage {
    SIMPLIFIED_CHINESE("zh", "zh-hans", "中文（简体）", Locale.SIMPLIFIED_CHINESE),
    HONG_KONG_CHINESE("zh-hk", "zh-hk", "繁體中文（香港）", Locale.of("zh", "HK")),
    TAIWAN_CHINESE("zh-tw", "zh-tw", "繁體中文（台灣）", Locale.TAIWAN),
    JAPANESE("ja", "ja", "日本語", Locale.JAPANESE),
    ENGLISH("en", "en", "English", Locale.ENGLISH);

    private final String code;
    private final String messageCode;
    private final String label;
    private final Locale locale;

    InterfaceLanguage(String code, String messageCode, String label, Locale locale) {
        this.code = code;
        this.messageCode = messageCode;
        this.label = label;
        this.locale = locale;
    }

    public String getCode() { return code; }
    public String getMessageCode() { return messageCode; }
    public String getLabel() { return label; }
    public Locale getLocale() { return locale; }

    public static InterfaceLanguage parse(String tag) {
        return tag == null ? null : fromLocale(Locale.forLanguageTag(tag.strip().replace('_', '-')));
    }

    public static InterfaceLanguage fromLocale(Locale locale) {
        if (locale == null) return null;
        return switch (locale.getLanguage()) {
            case "en" -> ENGLISH;
            case "ja" -> JAPANESE;
            case "zh" -> switch (locale.getCountry()) {
                case "HK", "MO" -> HONG_KONG_CHINESE;
                case "TW" -> TAIWAN_CHINESE;
                default -> "Hant".equals(locale.getScript()) ? TAIWAN_CHINESE : SIMPLIFIED_CHINESE;
            };
            default -> null;
        };
    }
}
