package cc.thonly.wikikernel.plugin;

import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 插件在「站点设置」页里的分区集合：把插件自己注册的 {@link SiteSettingsSection} 与
 * **描述文件里声明的功能开关**（{@code plugin.featureKey} / {@code plugin.featureDefault}）合并在一起。
 * <p>
 * 功能开关的处理：插件只在自己的 {@code META-INF/wiki-plugin.properties} 里写
 * <pre>
 *   plugin.featureKey=features.comments
 *   plugin.featureDefault=true
 * </pre>
 * core 就自动为它渲染一个复选框（键 = 声明的开关，标签 = 插件的本地化名，默认值 = 声明的默认值），
 * 保存时按同一批键写站点设置；插件自己用 {@code SiteConfig} 读同一个键。
 * <ul>
 *     <li>插件**自己注册了分区**（例如 page-banner / confirm-edit / registration-quiz，分区里已含开关）时，
 *     core 不再为它生成自动开关（避免设置页出现两个同样的复选框）；</li>
 *     <li>插件未加载时开关与分区都不存在——core 里没有任何插件开关的代码。</li>
 * </ul>
 */
@Component
public class PluginFeatureSections {

    /** 自动开关分区的排序（在插件自有分区之后）。 */
    private static final int AUTO_ORDER = 900;

    private final PluginManager pluginManager;
    private final MessageSource messageSource;

    public PluginFeatureSections(PluginManager pluginManager, MessageSource messageSource) {
        this.pluginManager = pluginManager;
        this.messageSource = messageSource;
    }

    /** 站点设置页要渲染的全部分区（按 order + id 排好）。 */
    public List<SiteSettingsSection> sections() {
        List<SiteSettingsSection> pluginSections = pluginManager.extensions(SiteSettingsSection.class);
        Set<String> ownIds = new LinkedHashSet<>();
        for (SiteSettingsSection section : pluginSections) {
            ownIds.add(section.id());
        }
        List<SiteSettingsSection> sections = new ArrayList<>(pluginSections);
        for (LoadedPlugin loaded : pluginManager.loadedPlugins()) {
            if (loaded.descriptor().hasFeatureKey() && !ownIds.contains(loaded.descriptor().id())) {
                sections.add(new AutoSwitchSection(loaded));
            }
        }
        sections.sort(Comparator.comparingInt(SiteSettingsSection::order).thenComparing(SiteSettingsSection::id));
        return List.copyOf(sections);
    }

    /** 某个已加载插件声明的功能开关（没声明 / 未加载时为空）。 */
    public Optional<FeatureSwitch> featureSwitch(String pluginId) {
        return pluginManager.find(pluginId)
                .filter(loaded -> loaded.descriptor().hasFeatureKey())
                .map(loaded -> new FeatureSwitch(loaded.descriptor().id(),
                        loaded.descriptor().featureKey(), loaded.descriptor().featureDefault()));
    }

    /** 一个插件声明的功能开关。 */
    public record FeatureSwitch(String pluginId, String key, boolean defaultValue) {
    }

    /** 描述文件声明了功能开关、且插件自己没注册分区的插件：core 自动生成的一个开关分区。 */
    private final class AutoSwitchSection implements SiteSettingsSection {

        private final LoadedPlugin loaded;

        private AutoSwitchSection(LoadedPlugin loaded) {
            this.loaded = loaded;
        }

        @Override
        public String id() {
            return loaded.descriptor().id();
        }

        @Override
        public String title() {
            return localized("plugin." + id() + ".name", loaded.descriptor().name());
        }

        @Override
        public String description() {
            return localized("plugin." + id() + ".description", loaded.descriptor().description());
        }

        @Override
        public int order() {
            return AUTO_ORDER;
        }

        @Override
        public List<SettingField> fields() {
            return List.of(SettingField.checkbox(loaded.descriptor().featureKey(),
                    localized("settings.pluginFeatureEnabled", "Enabled"),
                    loaded.descriptor().featureDefault()));
        }

        private String localized(String key, String fallback) {
            return messageSource.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
        }
    }

    /** 站点设置的布尔语义（与 core 的其它开关一致）。 */
    static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
                || "on".equals(normalized) || "yes".equals(normalized);
    }
}
