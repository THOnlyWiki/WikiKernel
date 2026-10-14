package cc.thonly.wikikernel.plugin.fingerprintlogin.model;

import cc.thonly.wikikernel.plugin.fingerprintlogin.service.FingerprintStore;
import cc.thonly.wikikernel.plugin.fingerprintlogin.FingerprintLoginSupport;

import java.util.List;
import java.util.Map;

/**
 * 管理界面面板：参数设置里的「指纹登录」分区与 {@code 特殊:指纹登录} 共用同一段 HTML 与同一批接口。
 * <p>
 * HTML 由插件拼（core 只做 {@code th:utext} 渲染），所以所有用户数据都要转义；
 * 指纹摘要**不渲染**（前端自己现算），只回显名称 / 时间 / UA 摘要。
 * <p>
 * 面板里只放一个 {@code id="fingerprint-login-panel"} 容器 + 一个隐藏的 JSON 配置输入，
 * 其余渲染与交互交给站点级脚本 {@code /plugins/fingerprint-login/fingerprint-login.js}。
 */
public final class FingerprintLoginPanel {

    /** 面板容器 id（脚本用）。 */
    public static final String PANEL_ID = "fingerprint-login-panel";

    /** 隐藏配置输入框 id（脚本读它拿文案与上限）。 */
    public static final String CONFIG_INPUT_ID = "fingerprint-login-config";

    private FingerprintLoginPanel() {
    }

    /**
     * 面板 HTML。
     *
     * @param text    取本地化文案（缺失时由调用方回显键名）
     * @param enabled 功能开关（关闭时只显示提示）
     * @param viewerId 当前用户 id（0 = 未登录）
     * @param rows    已登记的指纹（{@link FingerprintView#asModel()}）
     */
    public static String html(MessageLookup text, boolean enabled, long viewerId,
                             List<Map<String, Object>> rows) {
        StringBuilder html = new StringBuilder();
        html.append("<div id=\"").append(PANEL_ID).append("\" class=\"fingerprint-login-panel\"")
                .append(" data-enabled=\"").append(enabled).append('"')
                .append(" data-logged-in=\"").append(viewerId > 0).append('"')
                .append(" data-max=\"").append(maxPerUser()).append("\">")
                .append("<p class=\"wiki-hint\">").append(escape(text.get("fingerprint.preferences.intro")))
                .append("</p>");
        if (!enabled) {
            html.append("<p class=\"wiki-warning\">").append(escape(text.get("fingerprint.disabled")))
                    .append("</p>")
                    .append("</div>");
            return html.toString();
        }
        html.append("<p class=\"wiki-warning fingerprint-https-only\" hidden>")
                .append(escape(text.get("fingerprint.httpsOnly"))).append("</p>")
                .append("<p class=\"wiki-warning fingerprint-login-required\" hidden>")
                .append(escape(text.get("fingerprint.special.loginRequired"))).append("</p>")
                .append("<table class=\"wikitable fingerprint-list\"")
                .append(" data-empty-label=\"").append(escape(text.get("fingerprint.list.empty"))).append("\"")
                .append(" data-never-label=\"").append(escape(text.get("fingerprint.list.never"))).append("\"")
                .append(" data-remove-label=\"").append(escape(text.get("fingerprint.list.remove"))).append("\"")
                .append(" data-remove-confirm=\"").append(escape(text.get("fingerprint.list.removeConfirm")))
                .append("\"><thead><tr>")
                .append("<th>").append(escape(text.get("fingerprint.list.label"))).append("</th>")
                .append("<th>").append(escape(text.get("fingerprint.list.createdAt"))).append("</th>")
                .append("<th>").append(escape(text.get("fingerprint.list.lastUsedAt"))).append("</th>")
                .append("<th>").append(escape(text.get("fingerprint.list.userAgent"))).append("</th>")
                .append("<th></th></tr></thead><tbody class=\"fingerprint-rows\">");
        for (Map<String, Object> row : rows) {
            html.append(row(row, text.get("fingerprint.list.remove")));
        }
        html.append("</tbody></table>")
                .append("<h3>").append(escape(text.get("fingerprint.add.title"))).append("</h3>")
                .append("<div class=\"form-field\"><label for=\"fingerprint-label\">")
                .append(escape(text.get("fingerprint.add.label"))).append("</label>")
                .append("<input type=\"text\" id=\"fingerprint-label\" class=\"fingerprint-label\" maxlength=\"")
                .append(FingerprintLoginSupport.LABEL_MAX_LENGTH).append("\" placeholder=\"")
                .append(escape(text.get("fingerprint.add.labelPlaceholder"))).append("\"/></div>")
                .append("<div class=\"form-actions\"><button type=\"button\" class=\"wiki-button fingerprint-add\">")
                .append(escape(text.get("fingerprint.add.submit"))).append("</button></div>")
                .append("<p class=\"wiki-hint\">").append(escape(text.get("fingerprint.add.hint"))).append("</p>")
                .append("<p class=\"form-message fingerprint-message\" role=\"alert\" hidden></p>")
                .append("<input type=\"hidden\" id=\"").append(CONFIG_INPUT_ID).append("\" value=\"")
                .append(FingerprintLoginSupport.escape(configJson(text))).append("\"/>")
                .append("</div>");
        return html.toString();
    }

    /** 一行指纹（脚本新增行时也用同一套字段名，便于统一删除逻辑）。 */
    private static String row(Map<String, Object> row, String removeLabel) {
        return "<tr class=\"fingerprint-row\" data-id=\"" + row.get("id") + "\">"
                + "<td class=\"fingerprint-row-label\">" + escape(String.valueOf(row.get("label"))) + "</td>"
                + "<td class=\"fingerprint-row-created\">" + escape(String.valueOf(row.get("createdAtText"))) + "</td>"
                + "<td class=\"fingerprint-row-used\">"
                + escape(row.get("used") == Boolean.TRUE ? String.valueOf(row.get("lastUsedText")) : "")
                + "</td>"
                + "<td class=\"fingerprint-row-agent\">" + escape(String.valueOf(row.get("userAgent"))) + "</td>"
                + "<td><button type=\"button\" class=\"wiki-button fingerprint-remove\">" + escape(removeLabel)
                + "</button></td></tr>";
    }

    /** 脚本要用的文案与上限（JSON；值都用 {@link FingerprintLoginSupport#json} 转义）。 */
    public static String configJson(MessageLookup text) {
        return "{"
                + "\"messages\":{"
                + "\"empty\":" + FingerprintLoginSupport.json(text.get("fingerprint.list.empty")) + ","
                + "\"never\":" + FingerprintLoginSupport.json(text.get("fingerprint.list.never")) + ","
                + "\"remove\":" + FingerprintLoginSupport.json(text.get("fingerprint.list.remove")) + ","
                + "\"removeConfirm\":" + FingerprintLoginSupport.json(text.get("fingerprint.list.removeConfirm")) + ","
                + "\"working\":" + FingerprintLoginSupport.json(text.get("fingerprint.login.working")) + ","
                + "\"notSupported\":" + FingerprintLoginSupport.json(text.get("fingerprint.login.notSupported")) + ","
                + "\"httpsOnly\":" + FingerprintLoginSupport.json(text.get("fingerprint.httpsOnlyHint")) + ","
                + "\"loginRequired\":" + FingerprintLoginSupport.json(text.get("fingerprint.error.loginRequired")) + ","
                + "\"addSuccess\":" + FingerprintLoginSupport.json(text.get("fingerprint.add.success")) + ","
                + "\"addUpdated\":" + FingerprintLoginSupport.json(text.get("fingerprint.add.updated")) + ","
                + "\"removeSuccess\":" + FingerprintLoginSupport.json(text.get("fingerprint.remove.success")) + ","
                + "\"networkError\":" + FingerprintLoginSupport.json(text.get("fingerprint.login.networkError")) + ","
                + "\"error\":" + FingerprintLoginSupport.json(text.get("fingerprint.login.error")) + ","
                + "\"success\":" + FingerprintLoginSupport.json(text.get("fingerprint.login.success")) + ","
                + "\"maxReached\":" + FingerprintLoginSupport.json(text.get("fingerprint.maxReached")) + "}"
                + ",\"max\":" + maxPerUser()
                + "}";
    }

    /** 每用户上限（来自 common 的桥接口常量，插件与 core 只有一个来源）。 */
    public static int maxPerUser() {
        return FingerprintStore.MAX_PER_USER;
    }

    private static String escape(String value) {
        return FingerprintLoginSupport.escape(value);
    }

    /** 取本地化文案的最小接口（{@code PreferenceContext} / {@code FingerprintLoginSupport} 都能适配）。 */
    @FunctionalInterface
    public interface MessageLookup {

        String get(String key);
    }
}
