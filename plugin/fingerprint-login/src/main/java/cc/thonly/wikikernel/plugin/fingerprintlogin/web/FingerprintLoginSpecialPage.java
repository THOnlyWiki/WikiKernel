package cc.thonly.wikikernel.plugin.fingerprintlogin.web;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.plugin.fingerprintlogin.FingerprintLoginSupport;
import cc.thonly.wikikernel.plugin.fingerprintlogin.model.FingerprintLoginPanel;
import cc.thonly.wikikernel.plugin.fingerprintlogin.service.FingerprintLoginService;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:指纹登录} / {@code Special:FingerprintLogin}：管理**自己**的浏览器指纹
 * （列出 / 添加当前浏览器 / 删除），与参数设置里的分区共用同一批接口。
 * <p>
 * 未登录时只显示登录提示（不列任何数据）；管理员也看不到别人的指纹（隐私），
 * 但可以在站点设置里关闭整个功能（{@code features.fingerprint-login}）。
 */
public class FingerprintLoginSpecialPage implements SpecialPage {

    private final FingerprintLoginService service;
    private final FingerprintLoginSupport support;

    public FingerprintLoginSpecialPage(FingerprintLoginService service, FingerprintLoginSupport support) {
        this.service = service;
        this.support = support;
    }

    @Override
    public String name() {
        return "指纹登录";
    }

    @Override
    public String displayName() {
        return support.message("fingerprint.page.title");
    }

    @Override
    public List<String> aliases() {
        return List.of("FingerprintLogin", "Fingerprint login");
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String template() {
        // 插件模板放 jar 内 templates/（core 的 PluginTemplateResolver 会从插件类加载器读取）
        return "special/fingerprint-login";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        long userId = user == null || user.getId() == null ? 0L : user.getId();
        boolean enabled = support.enabled();
        boolean listable = enabled && userId > 0;
        List<Map<String, Object>> rows = listable ? service.listRows(userId) : List.of();
        model.put("enabled", enabled);
        model.put("loggedIn", userId > 0);
        model.put("preferencesUrl", "/wiki/特殊:参数设置");
        model.put("fingerprints", rows);
        model.put("panelHtml", FingerprintLoginPanel.html(support::message, enabled, userId, rows));
        model.put("panelConfigJson", FingerprintLoginPanel.configJson(support::message));
    }
}
