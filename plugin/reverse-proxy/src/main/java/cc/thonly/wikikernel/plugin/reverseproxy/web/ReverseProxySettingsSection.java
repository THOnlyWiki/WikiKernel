package cc.thonly.wikikernel.plugin.reverseproxy.web;

import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.plugin.reverseproxy.ReverseProxySupport;
import cc.thonly.wikikernel.plugin.reverseproxy.service.ReverseProxyConfigService;

import java.util.List;

/** 站点设置里的反向代理全局设置（当前只有出站代理）。 */
public class ReverseProxySettingsSection implements SiteSettingsSection {

    private final ReverseProxySupport support;

    public ReverseProxySettingsSection(ReverseProxySupport support) {
        this.support = support;
    }

    @Override
    public String id() {
        return "reverse-proxy";
    }

    @Override
    public String title() {
        return support.message("settings.reverseProxy");
    }

    @Override
    public String description() {
        return support.message("settings.reverseProxyHint");
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public List<SettingField> fields() {
        return List.of(
                SettingField.checkbox(ReverseProxyConfigService.FEATURE_KEY,
                        support.message("settings.reverseProxyEnabled"), true),
                SettingField.text(ReverseProxyConfigService.OUTBOUND_PROXY_KEY,
                        support.message("settings.reverseProxyOutboundProxy"), ""));
    }
}
