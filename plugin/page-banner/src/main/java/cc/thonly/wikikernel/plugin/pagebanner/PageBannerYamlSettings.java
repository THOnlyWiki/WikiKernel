package cc.thonly.wikikernel.plugin.pagebanner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LocalSettings.yaml 的页面横幅配置（{@code wikikernel.features.page-banner.*}，与迁移前 core 的
 * {@code FeatureProperties.PageBanner} **同一批键**）。
 * <p>
 * 插件在 {@code onStart} 里把它导入站点设置（{@code SiteConfig.set}），与迁移前 core 的「yaml 导入」
 * 行为一致：yaml 里写了的值每次启动覆盖一次，没写的键不动；之后站点设置页 / 插件设置分区改的是同一批键。
 */
@Component
@ConfigurationProperties(prefix = "wikikernel.features.page-banner")
public class PageBannerYamlSettings {

    /** 是否启用页面横幅；{@code null} = 不改站点设置。 */
    private Boolean enabled;

    /** 横幅图片池（文件页名称；多个）。 */
    private List<String> files;

    /** 是否只对白名单页面显示横幅。 */
    private Boolean whitelistEnabled;

    /** 横幅白名单页面标题。 */
    private List<String> whitelist;

    /** 轮换机制：stable（默认）/ random / rotate / daily / hourly。 */
    private String rotation;

    /** 逐页配置（{@code 页面标题=横幅文件名}，文件名可省）。 */
    private List<String> perPage;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getFiles() {
        return files;
    }

    public void setFiles(List<String> files) {
        this.files = files;
    }

    public Boolean getWhitelistEnabled() {
        return whitelistEnabled;
    }

    public void setWhitelistEnabled(Boolean whitelistEnabled) {
        this.whitelistEnabled = whitelistEnabled;
    }

    public List<String> getWhitelist() {
        return whitelist;
    }

    public void setWhitelist(List<String> whitelist) {
        this.whitelist = whitelist;
    }

    public String getRotation() {
        return rotation;
    }

    public void setRotation(String rotation) {
        this.rotation = rotation;
    }

    public List<String> getPerPage() {
        return perPage;
    }

    public void setPerPage(List<String> perPage) {
        this.perPage = perPage;
    }
}
