package cc.thonly.wikikernel.plugin.pagebanner;

import cc.thonly.wikikernel.common.extension.SetupContext;
import cc.thonly.wikikernel.common.extension.SetupField;
import cc.thonly.wikikernel.common.extension.SetupResult;
import cc.thonly.wikikernel.common.extension.SetupStep;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 页面横幅的安装向导步骤（{@code /init} 里本插件的 tab）：
 * 字段与迁移前 core 向导里的页面横幅一组完全一致，{@link #apply} 把值写进
 * {@code wikikernel.features.page-banner.*}（YAML），重启后由插件导入站点设置。
 * <p>
 * core 的向导不再认识页面横幅——插件没加载时向导里也不会出现这一组字段。
 */
public class PageBannerSetupStep implements SetupStep {

    private final MessageSource messages;

    public PageBannerSetupStep(MessageSource messages) {
        this.messages = messages;
    }

    @Override
    public String id() {
        return "page-banner";
    }

    @Override
    public String displayName() {
        return text("settings.pageBannerTitle");
    }

    @Override
    public String description() {
        return text("setup.pageBannerHint");
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public List<SetupField> fields() {
        return List.of(
                SetupField.checkbox("enabled", text("setup.pageBanner"), true),
                SetupField.text("files", text("setup.pageBannerFiles"), ""),
                SetupField.checkbox("whitelistEnabled", text("setup.pageBannerWhitelistEnabled"), true),
                SetupField.text("whitelist", text("setup.pageBannerWhitelist"), ""),
                SetupField.select("rotation", text("setup.pageBannerRotation"), "stable",
                        PageBannerSettings.ROTATION_MODES),
                SetupField.text("perPage", text("setup.pageBannerPerPage"), ""));
    }

    @Override
    public SetupResult apply(Map<String, String> values, SetupContext context) {
        context.putGlobal("wikikernel.features.page-banner.enabled", truthy(values.get("enabled")));
        context.putGlobal("wikikernel.features.page-banner.files", splitList(values.get("files")));
        context.putGlobal("wikikernel.features.page-banner.whitelist-enabled",
                truthy(values.get("whitelistEnabled")));
        context.putGlobal("wikikernel.features.page-banner.whitelist", splitList(values.get("whitelist")));
        String rotation = values.get("rotation");
        context.putGlobal("wikikernel.features.page-banner.rotation",
                rotation == null || rotation.isBlank() ? "stable" : rotation.trim());
        context.putGlobal("wikikernel.features.page-banner.per-page", splitList(values.get("perPage")));
        return SetupResult.ok(text("setup.pageBannerSaved"));
    }

    /** {@code |} 或换行分隔 → 列表（与迁移前 core 向导的 splitList 一致；空项去掉）。 */
    private static List<String> splitList(String raw) {
        List<String> items = new ArrayList<>();
        if (raw == null) {
            return items;
        }
        for (String item : raw.split("[|\\r\\n]+")) {
            String text = item.trim();
            if (!text.isEmpty()) {
                items.add(text);
            }
        }
        return items;
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
                || "on".equals(normalized) || "yes".equals(normalized);
    }

    private String text(String key) {
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
