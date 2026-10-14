package cc.thonly.wikikernel.plugin.hitcounters;

import cc.thonly.wikikernel.common.extension.SetupContext;
import cc.thonly.wikikernel.common.extension.SetupField;
import cc.thonly.wikikernel.common.extension.SetupResult;
import cc.thonly.wikikernel.common.extension.SetupStep;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Map;

/**
 * HitCounters 的安装向导入口（写入 {@code wikikernel.plugins.settings.hit-counters.*}）。
 * <p>
 * 文案走插件自己的消息包（{@code i18n/messages*.properties} 的 {@code setup.hit-counters.*} 键）。
 */
public class HitCountersSetup implements SetupStep {

    private final HitCountersConfig current;
    private final MessageSource messageSource;

    public HitCountersSetup(HitCountersConfig current, MessageSource messageSource) {
        this.current = current;
        this.messageSource = messageSource;
    }

    @Override
    public String id() {
        return "hit-counters";
    }

    @Override
    public String displayName() {
        return "HitCounters";
    }

    @Override
    public String description() {
        return text("setup.hit-counters.description");
    }

    @Override
    public int order() {
        return 120;
    }

    @Override
    public List<SetupField> fields() {
        return List.of(
                SetupField.checkbox("disabled", text("setup.hit-counters.disabled"), current.disabled()),
                SetupField.checkbox("footer", text("setup.hit-counters.footer"), current.footer()),
                SetupField.checkbox("enable-add-page-id", text("setup.hit-counters.enable-add-page-id"),
                        current.addPageId()),
                SetupField.checkbox("enable-add-text-length", text("setup.hit-counters.enable-add-text-length"),
                        current.addTextLength()),
                SetupField.text("exempt-groups", text("setup.hit-counters.exempt-groups"),
                        String.join(",", current.exemptGroups())));
    }

    @Override
    public SetupResult apply(Map<String, String> values, SetupContext context) {
        context.putPluginSetting("disabled", checkbox(values, "disabled", false));
        context.putPluginSetting("footer", checkbox(values, "footer", true));
        context.putPluginSetting("enable-add-page-id", checkbox(values, "enable-add-page-id", false));
        context.putPluginSetting("enable-add-text-length", checkbox(values, "enable-add-text-length", false));
        context.putPluginSetting("exempt-groups", values.getOrDefault("exempt-groups", "").trim());
        return SetupResult.ok(text("setup.hit-counters.saved"));
    }

    /** 取一条本地化文案（缺失时显示键名，见 {@link HitCountersSupport#text}）。 */
    private String text(String key) {
        return HitCountersSupport.text(messageSource, key);
    }

    private static String checkbox(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        return value == null ? Boolean.toString(fallback) : Boolean.toString("true".equalsIgnoreCase(value));
    }
}
