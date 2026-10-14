package cc.thonly.wikikernel.plugin.confirmedit;

import cc.thonly.wikikernel.common.extension.SetupContext;
import cc.thonly.wikikernel.common.extension.SetupField;
import cc.thonly.wikikernel.common.extension.SetupResult;
import cc.thonly.wikikernel.common.extension.SetupStep;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 编辑验证码的安装向导步骤（{@code /init} 里本插件的 tab）：字段与迁移前 core 向导里的
 * ConfirmEdit 一组一致，{@link #apply} 写 {@code wikikernel.features.confirm-edit.*}（YAML），
 * 重启后由插件导入站点设置。core 的向导不再认识编辑验证码。
 */
public class ConfirmEditSetupStep implements SetupStep {

    private final MessageSource messages;

    public ConfirmEditSetupStep(MessageSource messages) {
        this.messages = messages;
    }

    @Override
    public String id() {
        return "confirm-edit";
    }

    @Override
    public String displayName() {
        return text("settings.confirmEdit");
    }

    @Override
    public String description() {
        return text("setup.confirmEditHint");
    }

    @Override
    public int order() {
        return 210;
    }

    @Override
    public List<SetupField> fields() {
        return List.of(
                SetupField.checkbox("enabled", text("setup.confirmEdit"), false),
                SetupField.text("bypassGroups", text("setup.confirmEditBypass"),
                        ConfirmEditSettings.DEFAULT_BYPASS_GROUPS));
    }

    @Override
    public SetupResult apply(Map<String, String> values, SetupContext context) {
        context.putGlobal("wikikernel.features.confirm-edit.enabled", truthy(values.get("enabled")));
        context.putGlobal("wikikernel.features.confirm-edit.bypass-groups", splitList(values.get("bypassGroups")));
        return SetupResult.ok(text("setup.confirmEditSaved"));
    }

    /** {@code |} 或换行分隔 → 列表（与迁移前 core 向导一致；空项去掉）。 */
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
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
                || "on".equals(normalized) || "yes".equals(normalized);
    }

    private String text(String key) {
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
