package cc.thonly.wikikernel.plugin.fingerprintlogin.hook;

import cc.thonly.wikikernel.common.extension.PreferenceContext;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.fingerprintlogin.FingerprintLoginSupport;
import cc.thonly.wikikernel.plugin.fingerprintlogin.model.FingerprintLoginPanel;
import cc.thonly.wikikernel.plugin.fingerprintlogin.service.FingerprintLoginService;

import java.util.List;

/**
 * 参数设置（{@code 特殊:参数设置}）里的「指纹登录」分区：列出自己的浏览器指纹、添加当前浏览器、删除。
 * <p>
 * 与 {@code 特殊:指纹登录} 共用同一段面板 HTML 与同一批接口
 * （{@code GET/POST/DELETE /api/fingerprint-login/list|add|remove}），
 * 交互由站点级脚本 {@code /plugins/fingerprint-login/fingerprint-login.js} 完成。
 * <p>
 * 隐私：这里只列**当前用户自己**的指纹；管理员也看不到别人的列表（没有按用户查询的入口）。
 */
public class FingerprintLoginPreferenceSection implements PreferenceSection {

    private final FingerprintLoginService service;
    private final FingerprintLoginSupport support;

    public FingerprintLoginPreferenceSection(FingerprintLoginService service, FingerprintLoginSupport support) {
        this.service = service;
        this.support = support;
    }

    @Override
    public String id() {
        return "fingerprint-login";
    }

    @Override
    public String labelKey() {
        return "fingerprint.preferences.title";
    }

    @Override
    public String fallbackLabel() {
        // 给人看的名字走 labelKey；这里只留英文技术名，避免漏翻时漏出中文
        return "Fingerprint login";
    }

    @Override
    public int order() {
        // Gadgets 是 100、AI 代理是 110，这里排在它们后面
        return 120;
    }

    @Override
    public String html(PreferenceContext context) {
        Viewer viewer = context == null ? null : context.viewer();
        long userId = viewer == null || !viewer.loggedIn() ? 0L : viewer.id();
        // 分区文案用 context.message(key, null)：漏翻时显示键名（不写中文兜底）
        FingerprintLoginPanel.MessageLookup text = key -> context.message(key, null);
        return FingerprintLoginPanel.html(text, support.enabled(), userId,
                userId > 0 ? service.listRows(userId) : List.of());
    }
}
