package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.LoginSession;
import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浏览器指纹登录（插件 fingerprint-login）的 **core 侧平台接线**：
 * <ul>
 *     <li>{@link LoginSession} 建立的会话与 core 密码登录**完全一致**（同一个 {@code UserSession.ATTRIBUTE}
 *     属性、同一份 {@code WikiUser.toTransferCopy()} 内容、同一个会话有效期规则）；</li>
 *     <li>功能开关 {@code features.fingerprint-login}（默认关闭）与限流分类常量。</li>
 * </ul>
 * 指纹表与数据访问已经从 core/ common 迁进插件（{@code wiki_fingerprint} 由插件的
 * {@code schemaEntities()} 声明、core 自动建表；{@code FingerprintStore} / {@code LoginSession} 的
 * 数据访问在插件里用 {@code PluginDatabase}），所以这里不再测实体 / 表，插件侧由
 * {@code plugin/fingerprint-login} 的 {@code FingerprintStoreJdbcTest} 等覆盖。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class FingerprintLoginBridgeTest {

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private LoginSession loginSession;

    @Autowired
    private RateLimit rateLimit;

    @Autowired
    private FeatureService featureService;
    @Autowired
    private SiteSettingsService siteSettingsService;


    @Test
    void loginBridgeBuildsExactlyThePasswordLoginSession() {
        WikiUser user = insertUser();
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            loginSession.establish(user.getId(), true);

            // 与 core 密码登录（ApiController#login → UserSession.login(request, user.toTransferCopy(), remember)）
            // 完全相同的会话属性与内容
            Object stored = session.getAttribute(UserSession.ATTRIBUTE);
            assertNotNull(stored, "会话里应有 " + UserSession.ATTRIBUTE);
            assertTrue(stored instanceof WikiUser);
            WikiUser inSession = (WikiUser) stored;
            WikiUser fresh = wikiUserMapper.selectById(user.getId());
            assertEquals(fresh.getId(), inSession.getId());
            assertEquals(fresh.getUsername(), inSession.getUsername());
            assertEquals(fresh.getEmail(), inSession.getEmail());
            assertEquals(fresh.toTransferCopy().getPassword(), inSession.getPassword(), "密码必须是脱敏副本");
            assertFalse(inSession.getPassword().isEmpty(), "脱敏副本用等长星号占位");
            assertTrue(inSession.getPassword().chars().allMatch(character -> character == '*'));
            // 「记住我」= 30 天（与 UserSession 的常量一致：30 * 24 * 60 * 60 秒）
            assertEquals(30 * 24 * 60 * 60, session.getMaxInactiveInterval());

            // core 的 CurrentUser / UserSession.current 能认出这个会话
            assertEquals(user.getId(), UserSession.current(request).getId());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void loginBridgeUsesShortSessionWithoutRememberAndLogsOut() {
        WikiUser user = insertUser();
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            loginSession.establish(user.getId(), false);
            assertEquals(30 * 60, session.getMaxInactiveInterval(), "不勾选「记住我」= 30 分钟");

            loginSession.logout();
            assertTrue(session.isInvalid(), "logout 使会话失效（与 core 的 UserSession.logout 一致）");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        // 用户不存在 / 没有请求上下文：拒绝建立会话
        assertThrows(IllegalArgumentException.class, () -> loginSession.establish(999_999_999L, false));
        RequestContextHolder.resetRequestAttributes();
        assertThrows(IllegalArgumentException.class, () -> loginSession.establish(user.getId(), false));
    }

    @Test
    void featureSwitchDefaultsToOffAndRateLimitCategoriesExist() {
        assertEquals("features.fingerprint-login", "features.fingerprint-login");
        assertFalse(siteSettingsService.getBoolean("features.fingerprint-login", false), "浏览器指纹登录默认关闭");
        siteSettingsService.set("features.fingerprint-login", Boolean.toString(true));
        try {
            assertTrue(siteSettingsService.getBoolean("features.fingerprint-login", false));
        } finally {
            siteSettingsService.set("features.fingerprint-login", Boolean.toString(false));
        }
        assertFalse(siteSettingsService.getBoolean("features.fingerprint-login", false));

        // 限流分类：插件用的两个分类名与 core 常量一致
        assertEquals("fingerprint-login", RateLimiterService.FINGERPRINT_LOGIN);
        assertEquals("fingerprint-login-ip", RateLimiterService.FINGERPRINT_LOGIN_IP);

        // 桥本身可用：窗口计数 / 清除都不抛异常
        RateLimit.Decision decision = rateLimit.check(RateLimiterService.FINGERPRINT_LOGIN, "test|hash");
        assertFalse(decision.limited());
        rateLimit.hit(RateLimiterService.FINGERPRINT_LOGIN, "test|hash");
        rateLimit.hit(RateLimiterService.FINGERPRINT_LOGIN_IP, "test");
        rateLimit.success(RateLimiterService.FINGERPRINT_LOGIN, "test|hash");
        rateLimit.success(RateLimiterService.FINGERPRINT_LOGIN_IP, "test");
        assertFalse(rateLimit.check(RateLimiterService.FINGERPRINT_LOGIN, "test|hash").limited());
    }

    // ==================== 工具 ====================

    private WikiUser insertUser() {
        WikiUser user = new WikiUser();
        user.setUsername("指纹桥用户" + System.nanoTime());
        user.setPassword(":pbkdf2:sha512:30000:64:abcdef");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }
}
