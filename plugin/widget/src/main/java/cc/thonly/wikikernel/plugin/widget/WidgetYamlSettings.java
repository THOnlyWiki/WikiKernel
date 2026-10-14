package cc.thonly.wikikernel.plugin.widget;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微件的旧配置兼容（{@code wikikernel.widgets.enabled}，来自迁移前 core 的 {@code WidgetProperties}）：
 * 插件在 {@code onStart} 里把它导入站点设置 {@code features.widgets}（false 时写 false；
 * 没写 / true 时不动站点设置，默认仍由 {@code features.widgets} 决定）。
 */
@Component
@ConfigurationProperties(prefix = "wikikernel.widgets")
public class WidgetYamlSettings {

    /** 旧配置里的开关；{@code null} = 不改站点设置。 */
    private Boolean enabled;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
