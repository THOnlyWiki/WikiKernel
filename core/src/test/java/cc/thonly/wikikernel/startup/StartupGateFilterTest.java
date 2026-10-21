package cc.thonly.wikikernel.startup;

import cc.thonly.wikikernel.config.SiteProperties;
import cc.thonly.wikikernel.config.SkinProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupGateFilterTest {

    private StartupGateFilter filter(StartupState state) {
        SiteProperties site = new SiteProperties();
        site.setName("测试站点");
        SkinProperties skins = new SkinProperties();
        skins.setDefaultSkin("vector");
        return new StartupGateFilter(state, site, skins);
    }

    @Test
    void blocksPagesAndApisUntilReady() throws Exception {
        StartupState state = new StartupState();
        StartupGateFilter filter = filter(state);

        MockHttpServletResponse page = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/wiki/首页"), page, (request, response) -> {
        });
        assertEquals(503, page.getStatus());
        assertTrue(page.getContentAsString().contains("正在启动 WikiKernel"));
        assertTrue(page.getContentAsString().contains("waiting-vector"));
        assertTrue(page.getContentAsString().contains("测试站点"));

        MockHttpServletResponse api = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/version"), api, (request, response) -> {
        });
        assertEquals(503, api.getStatus());
        assertTrue(api.getContentAsString().contains("\"code\":503"));

        MockHttpServletResponse ready = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/ready"), ready, (request, response) -> {
        });
        assertEquals(200, ready.getStatus());
        assertTrue(ready.getContentAsString().contains("\"ready\":false"));
    }

    @Test
    void passesEverythingThroughAfterReady() throws Exception {
        StartupState state = new StartupState();
        state.markReady();
        AtomicBoolean passed = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(state).doFilter(new MockHttpServletRequest("GET", "/wiki/首页"), response,
                (request, res) -> passed.set(true));
        assertTrue(passed.get());
        assertFalse(response.getContentAsString().contains("正在启动 WikiKernel"));
    }
}
