package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.web.WikiUrls;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 登录 / 注册 / 参数设置等模板 URL 必须输出百分号编码路径。 */
class WikiUrlHelperTest {

    private final WikiUrlHelper urls = WikiUrlHelper.INSTANCE;

    @Test
    void authLinksUseEncodedPaths() {
        String login = urls.login();
        assertEquals("/wiki/%E7%89%B9%E6%AE%8A%3A%E7%99%BB%E5%BD%95", login);
        assertFalse(login.contains("特殊:"), "不能把裸中文路径交给浏览器 / Tomcat");

        assertEquals("/wiki/%E7%89%B9%E6%AE%8A%3A%E6%B3%A8%E5%86%8C", urls.register());
        assertEquals("/wiki/%E7%89%B9%E6%AE%8A%3A%E5%8F%82%E6%95%B0%E8%AE%BE%E7%BD%AE", urls.preferences());
    }

    @Test
    void returnToIsEncodedAsAQueryValue() {
        String href = urls.loginTo("/wiki/特殊:首页");
        assertEquals("/wiki/%E7%89%B9%E6%AE%8A%3A%E7%99%BB%E5%BD%95"
                + "?returnto=/wiki/%E7%89%B9%E6%AE%8A%3A%E9%A6%96%E9%A1%B5", href);
        assertTrue(href.startsWith("/wiki/%E7%89%B9%E6%AE%8A%3A"));
    }

    @Test
    void encodePathEncodesPagePathButLeavesCoreRoutes() {
        assertEquals("/wiki/%E9%A6%96%E9%A1%B5", WikiUrls.encodePath("/wiki/首页"));
        assertEquals("/wiki/%E9%A6%96%E9%A1%B5?from=中",
                WikiUrls.encodePath("/wiki/首页?from=中"));
        assertEquals("/api/qq-email/bind/confirm", WikiUrls.encodePath("/api/qq-email/bind/confirm"));
        assertEquals("/", WikiUrls.encodePath("/"));
    }

    @Test
    void changePasswordUsesEncodedPreferenceReturnTo() {
        // returnto 本身是一个已编码的站内路径，作为查询值时要再按查询参数规则编码一次
        // （浏览器 / Servlet 解码一次后得到 /wiki/%E7...，改密页再做路径编码）。
        String expected = "/wiki/%E7%89%B9%E6%AE%8A%3A%E4%BF%AE%E6%94%B9%E5%AF%86%E7%A0%81"
                + "?returnto=/wiki/%25E7%2589%25B9%25E6%25AE%258A%253A%25E5%258F%2582%25E6%2595%25B0%25E8%25AE%25BE%25E7%25BD%25AE";
        assertEquals(expected, urls.changePassword(""));
        assertEquals(expected, urls.changePassword(urls.preferences()));
    }
}
