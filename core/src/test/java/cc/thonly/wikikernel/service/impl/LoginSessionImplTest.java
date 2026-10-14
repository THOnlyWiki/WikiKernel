package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录桥的不依赖 Spring 上下文的回归测试：{@link LoginSessionImpl} 必须与 core 密码登录
 * （{@code ApiController#login} → {@code UserSession.login(request, user.toTransferCopy(), remember)}）
 * 产生**完全相同**的会话状态。
 * <p>
 * 真实数据库 / Auto-DDL / 桥装配的覆盖在 {@code FingerprintLoginBridgeTest}（{@code @SpringBootTest}）；
 * 本类保证「会话内容一致」这条与 Spring 装配无关的契约始终可验证。
 */
class LoginSessionImplTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void establishMatchesThePasswordLoginSessionExactly() {
        WikiUser user = user(42L, "指纹登录用户", ":pbkdf2:sha512:30000:64:abcdef0123456789");
        LoginSessionImpl bridge = new LoginSessionImpl(userMapper(user));
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        bridge.establish(user.getId(), true);

        Object stored = session.getAttribute(UserSession.ATTRIBUTE);
        assertNotNull(stored, "会话属性名必须与 core 一致：" + UserSession.ATTRIBUTE);
        assertTrue(stored instanceof WikiUser);
        WikiUser inSession = (WikiUser) stored;

        // 与密码登录同一套内容：toTransferCopy()（密码脱敏）
        WikiUser expected = user.toTransferCopy();
        assertEquals(expected.getId(), inSession.getId());
        assertEquals(expected.getUsername(), inSession.getUsername());
        assertEquals(expected.getEmail(), inSession.getEmail());
        assertEquals(expected.getPassword(), inSession.getPassword(), "密码必须是脱敏副本");
        assertEquals(user.getPassword().length(), inSession.getPassword().length(), "脱敏用等长星号");
        assertTrue(inSession.getPassword().chars().allMatch(character -> character == '*'));
        assertFalse(inSession.getPassword().contains("pbkdf2"), "会话里不能出现口令哈希");

        // core 的读取路径能认出这个会话
        assertNotNull(UserSession.current(request));
        assertEquals(user.getId(), UserSession.current(request).getId());
        assertEquals(user.getUsername(), UserSession.current(request).getUsername());

        // 与 UserSession 的常量一致：记住我 = 30 天
        assertEquals(30 * 24 * 60 * 60, session.getMaxInactiveInterval());
    }

    @Test
    void establishUsesTheShortSessionWithoutRemember() {
        WikiUser user = user(7L, "甲", "hash");
        LoginSessionImpl bridge = new LoginSessionImpl(userMapper(user));
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        bridge.establish(user.getId(), false);
        assertEquals(30 * 60, session.getMaxInactiveInterval(), "不勾选「记住我」= 30 分钟");
    }

    @Test
    void establishRefreshesAnExistingSession() {
        WikiUser first = user(1L, "甲", "hash-a");
        WikiUser second = user(2L, "乙", "hash-b");
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        new LoginSessionImpl(userMapper(first)).establish(first.getId(), false);
        new LoginSessionImpl(userMapper(second)).establish(second.getId(), false);

        assertNotNull(UserSession.current(request));
        assertEquals(2L, UserSession.current(request).getId(), "同一个会话里换人 = 覆盖属性");
    }

    @Test
    void logoutInvalidatesTheSession() {
        WikiUser user = user(9L, "丙", "hash");
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        LoginSessionImpl bridge = new LoginSessionImpl(userMapper(user));

        bridge.establish(user.getId(), false);
        assertNotNull(UserSession.current(request));

        bridge.logout();
        assertTrue(session.isInvalid(), "与 core 的 UserSession.logout 一致：会话失效");
        assertNull(UserSession.current(request));
    }

    @Test
    void establishRejectsUnknownUsersAndMissingRequestContext() {
        // 用户不存在（Mapper 返回 null）
        LoginSessionImpl bridge = new LoginSessionImpl(userMapper(null));
        assertThrows(IllegalArgumentException.class, () -> bridge.establish(123L, false),
                "用户不存在时不建立会话");
        assertThrows(IllegalArgumentException.class, () -> bridge.establish(0L, false),
                "id <= 0 视为无效");
        assertThrows(IllegalArgumentException.class, () -> bridge.establish(1L, false),
                "没有请求上下文（后台任务 / 测试）时不建立会话");

        // 没有请求上下文时 logout 不抛异常（core 的 UserSession.logout 同样容忍）
        bridge.logout();
    }

    // ==================== 工具 ====================

    private static WikiUser user(long id, String name, String password) {
        WikiUser user = new WikiUser();
        user.setId(id);
        user.setUsername(name);
        user.setPassword(password);
        user.setEmail(name + "@example.org");
        return user;
    }

    /**
     * 只实现 {@code selectById} 的 {@link WikiUserMapper} 替身：
     * 用动态代理，避免实现 MyBatis-Plus {@code BaseMapper} 上的一堆方法。
     */
    private static WikiUserMapper userMapper(WikiUser user) {
        return (WikiUserMapper) Proxy.newProxyInstance(
                WikiUserMapper.class.getClassLoader(),
                new Class<?>[]{WikiUserMapper.class},
                (proxy, method, args) -> {
                    if ("selectById".equals(method.getName())) {
                        Object id = args == null || args.length == 0 ? null : args[0];
                        if (user == null || id == null
                                || !String.valueOf(user.getId()).equals(String.valueOf(id))) {
                            return null;
                        }
                        return user;
                    }
                    if ("toString".equals(method.getName())) {
                        return "WikiUserMapperFake";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == int.class) {
                        return 0;
                    }
                    if (returnType == long.class) {
                        return 0L;
                    }
                    if (returnType == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }
}
