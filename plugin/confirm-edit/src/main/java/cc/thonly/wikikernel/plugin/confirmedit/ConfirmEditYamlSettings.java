package cc.thonly.wikikernel.plugin.confirmedit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LocalSettings.yaml 的编辑验证码配置（{@code wikikernel.features.confirm-edit.*}，与迁移前 core 的
 * {@code FeatureProperties.ConfirmEdit} **同一批键**）：插件在 {@code onStart} 里导入站点设置
 * （写了的值每次启动覆盖一次；之后设置页 / 安装向导写的是同一批键）。
 */
@Component
@ConfigurationProperties(prefix = "wikikernel.features.confirm-edit")
public class ConfirmEditYamlSettings {

    /** 是否启用编辑验证码；{@code null} = 不改站点设置。 */
    private Boolean enabled;

    /** 免验证的用户组（权限组名；多个）。 */
    private List<String> bypassGroups;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getBypassGroups() {
        return bypassGroups;
    }

    public void setBypassGroups(List<String> bypassGroups) {
        this.bypassGroups = bypassGroups;
    }
}
