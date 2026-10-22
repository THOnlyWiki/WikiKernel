package cc.thonly.wikikernel.plugin.reverseproxy.web;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.plugin.reverseproxy.ReverseProxySupport;

import java.util.List;

/** 管理页的样式与脚本（全站注入但脚本只识别 #reverse-proxy-form，不会影响其它页面）。 */
public class ReverseProxyHeadProvider implements HtmlHeadProvider {

    private final ReverseProxySupport support;

    public ReverseProxyHeadProvider(ReverseProxySupport support) {
        this.support = support;
    }

    @Override
    public int order() {
        return 700;
    }

    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        String messages = "window.WIKI_MESSAGES = Object.assign(window.WIKI_MESSAGES || {}, {"
                + "reverseProxyRequired:" + ReverseProxySupport.jsString(
                support.message("reverseProxy.form.required")) + ","
                + "reverseProxyNetworkError:" + ReverseProxySupport.jsString(
                support.message("reverseProxy.networkError")) + ","
                + "reverseProxyOperationFailed:" + ReverseProxySupport.jsString(
                support.message("reverseProxy.operationFailed")) + ","
                + "reverseProxySaved:" + ReverseProxySupport.jsString(
                support.message("reverseProxy.saved")) + ","
                + "reverseProxyDeleted:" + ReverseProxySupport.jsString(
                support.message("reverseProxy.deleted")) + ","
                + "reverseProxyDeleteConfirm:" + ReverseProxySupport.jsString(
                support.message("reverseProxy.deleteConfirm")) + ","
                + "reverseProxyCsrfFailed:" + ReverseProxySupport.jsString(
                support.message("reverseProxy.csrfFailed")) + ","
                + "reverseProxyBadRequest:" + ReverseProxySupport.jsString(
                support.message("reverseProxy.badRequest"))
                + "});";
        return List.of(
                "<link rel=\"stylesheet\" href=\"/plugins/reverse-proxy/admin.css\">",
                "<script>" + messages + "</script>",
                "<script defer src=\"/plugins/reverse-proxy/admin.js\"></script>");
    }
}
