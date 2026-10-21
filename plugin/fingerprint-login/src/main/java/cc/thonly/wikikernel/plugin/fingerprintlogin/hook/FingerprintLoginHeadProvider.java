package cc.thonly.wikikernel.plugin.fingerprintlogin.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.plugin.fingerprintlogin.FingerprintLoginSupport;

import java.util.List;

/**
 * 浏览器指纹登录的前端注入（站点级；脚本自己判断当前页面是不是登录页 / 管理页）：
 * <ul>
 *     <li>{@code window.WIKI_CONFIG.fingerprintLogin = true}；</li>
 *     <li>登录页文案（{@code window.WIKI_MESSAGES}）；</li>
 *     <li>{@code /plugins/fingerprint-login/fingerprint-login.js}（浏览器端采集分量 + SHA-256，
 *     登录页插入「用浏览器指纹登录」按钮，管理页插入列表 / 添加 / 删除交互）。</li>
 * </ul>
 * 功能关闭时**什么都不注入**（登录页按钮不出现，接口也返回禁用提示）。
 */
public class FingerprintLoginHeadProvider implements HtmlHeadProvider {

    private final FingerprintLoginSupport support;

    public FingerprintLoginHeadProvider(FingerprintLoginSupport support) {
        this.support = support;
    }

    @Override
    public int order() {
        // 与 multi-upload（750）错开，保证注入顺序稳定
        return 760;
    }

    @Override
    public boolean siteWide() {
        // 登录页 / 参数设置 / 特殊:指纹登录 都是「非词条阅读视图」，必须站点级注入
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (!support.enabled()) {
            return List.of();
        }
        StringBuilder script = new StringBuilder("<script>window.WIKI_CONFIG = window.WIKI_CONFIG || {};")
                .append("window.WIKI_CONFIG.fingerprintLogin = true;")
                .append("window.WIKI_CONFIG.fingerprintLoginHttpsOnly = ").append(support.httpsOnly()).append(';')
                .append("window.WIKI_MESSAGES = Object.assign(window.WIKI_MESSAGES || {}, {")
                .append("fingerprintLoginButton: ").append(json("fingerprint.login.button")).append(',')
                .append("fingerprintLoginHint: ").append(json("fingerprint.login.buttonHint")).append(',')
                .append("fingerprintLoginRemember: ").append(json("fingerprint.login.remember")).append(',')
                .append("fingerprintLoginWorking: ").append(json("fingerprint.login.working")).append(',')
                .append("fingerprintLoginNotSupported: ").append(json("fingerprint.login.notSupported")).append(',')
                .append("fingerprintLoginSuccess: ").append(json("fingerprint.login.success")).append(',')
                .append("fingerprintLoginError: ").append(json("fingerprint.login.error")).append(',')
                .append("fingerprintLoginNetworkError: ").append(json("fingerprint.login.networkError")).append(',')
                .append("fingerprintHttpsOnly: ").append(json("fingerprint.httpsOnly")).append(',')
                .append("fingerprintDisabled: ").append(json("fingerprint.disabled"))
                .append("});</script>");
        return List.of(script.toString(),
                "<link rel=\"stylesheet\" href=\"/plugins/fingerprint-login/fingerprint-login.css\">",
                "<script defer src=\"/plugins/fingerprint-login/fingerprint-login.js\"></script>");
    }

    private String json(String key) {
        return FingerprintLoginSupport.json(support.message(key));
    }
}
