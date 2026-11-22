package cc.thonly.wikikernel.wikitext;

import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.regex.Pattern;

/** Parser-maintained categories whose names may be changed through interface messages. */
public final class TrackingCategories {
    public static final String HIDDEN_CATEGORY = "hidden-category-category";
    public static final String HIDDEN_PROPERTY = "hiddencat";
    static final Pattern HIDDEN_SWITCH = Pattern.compile("__(?:HIDDENCAT|隐藏分类|隱藏分類|カテゴリ非表示|カテ非表示|非表示カテ|隠しカテゴリ)__",
            Pattern.CASE_INSENSITIVE);

    private TrackingCategories() {}

    public static String categoryTitle(MessageSource messages, String key) {
        String category = messages == null ? "隐藏分类"
                : messages.getMessage(key, null, "隐藏分类", Locale.SIMPLIFIED_CHINESE);
        if (category == null || category.isBlank() || "-".equals(category.strip())) {
            return "";
        }
        String title = category.strip();
        return WikiLinkScanner.isCategoryTitle(title) ? WikiLinkScanner.canonicalCategoryTitle(title)
                : WikiLinkScanner.CATEGORY_PREFIX + title;
    }
}
