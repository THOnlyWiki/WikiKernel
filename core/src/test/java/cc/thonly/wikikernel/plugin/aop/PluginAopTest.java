package cc.thonly.wikikernel.plugin.aop;

import cc.thonly.wikikernel.config.PluginProperties;
import cc.thonly.wikikernel.config.StaticResourceCharsetFilter;
import cc.thonly.wikikernel.http.ClientInfoFilter;
import cc.thonly.wikikernel.http.ResponseBufferFilter;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.setup.SetupFilter;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件 AOP（{@code CoreAopHook} → {@link PluginAopRegistry} → {@link PluginAopBridge}）的 core 侧测试：
 * 不依赖任何插件 jar，直接把 Advisor 登记进登记处，验证「插件切面对 core Bean 生效」的全部语义。
 * <p>
 * 这里显式打开 {@code wikikernel.plugins.aop-bridge=true}（默认 {@code auto} 只在装了插件时才建代理），
 * 并把插件目录指向一个不存在的路径，因此本用例的行为完全由测试自己登记的 Advisor 决定。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.plugins.dir=build/no-plugins-for-aop-test",
        "wikikernel.plugins.classpath-scan=false",
        "wikikernel.plugins.aop-bridge=true"
})
class PluginAopTest {

    private static final String OWNER = "aop-test";

    @Autowired
    private PluginAopRegistry registry;

    @Autowired
    private PluginAopBridge aopBridge;

    @Autowired
    private SiteSettingsService siteSettingsService;

    @Autowired
    private InjectedHolder injectedHolder;

    @Autowired
    private MessageSource messageSource;

    @Autowired
    private PluginProperties pluginProperties;

    @Autowired
    private ResponseBufferFilter responseBufferFilter;

    @Autowired
    private ClientInfoFilter clientInfoFilter;

    @Autowired
    private SetupFilter setupFilter;

    @Autowired
    private StaticResourceCharsetFilter staticResourceCharsetFilter;

    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void clearAdvisors() {
        registry.removeOwner(OWNER);
    }

    @Test
    void coreBeanIsProxiedAndAdvisorRuns() {
        registry.addAdvisor(OWNER, countingAdvisor());

        assertTrue(AopUtils.isAopProxy(siteSettingsService), "core Bean 应当被桥接代理包起来");

        int before = calls.get();
        String value = siteSettingsService.get("aop.test.key", "fallback-value");

        assertEquals("fallback-value", value, "切面必须原样返回被调用方法的返回值");
        assertEquals(before + 1, calls.get(), "环绕通知应当被命中一次");
    }

    /** 出问题时用来定位链路断在哪一步（登记处 → 匹配 → 通知链 → 代理）。 */
    @Test
    void bridgeWiringIsVisibleStepByStep() throws Exception {
        registry.addAdvisor(OWNER, countingAdvisor());
        Method method = SiteSettingsService.class.getMethod("get", String.class, String.class);

        assertEquals(1, registry.advisors().size(), "① 登记处应当有 1 个 Advisor");
        assertTrue(registry.matches(method, siteSettingsService.getClass()), "② pointcut 应当命中");
        assertFalse(registry.chainFor(method, siteSettingsService.getClass()).isEmpty(), "③ 通知链不应为空");
        assertTrue(aopBridge.isBridged("siteSettingsServiceImpl"), "④ Bean 应当建过桥接代理");
        assertTrue(AopUtils.isAopProxy(siteSettingsService), "⑤ 注入的就是代理");
    }

    @Test
    void injectedReferencesPointAtTheProxy() {
        registry.addAdvisor(OWNER, countingAdvisor());

        assertSame(siteSettingsService, injectedHolder.injectedReference,
                "构造器注入的引用必须也是代理，否则切面对真实请求链路无效");
        assertTrue(AopUtils.isAopProxy(injectedHolder.injectedReference));
    }

    @Test
    void methodsNotMatchedByThePointcutAreNotAdvised() {
        registry.addAdvisor(OWNER, countingAdvisor());

        int before = calls.get();
        boolean value = siteSettingsService.getBoolean("aop.test.key", false);

        assertFalse(value);
        assertEquals(before, calls.get(), "pointcut 未命中的方法不应进入通知链");
    }

    @Test
    void removingAdvisorStopsAffectingCore() {
        registry.addAdvisor(OWNER, countingAdvisor());
        siteSettingsService.get("aop.test.key", "first");
        int afterFirst = calls.get();
        assertTrue(afterFirst > 0, "先确认切面生效");

        registry.removeOwner(OWNER);
        siteSettingsService.get("aop.test.key", "second");

        assertEquals(afterFirst, calls.get(), "撤销 Advisor（等价于插件卸载）后不应再计数");
    }

    @Test
    void brokenAdvisorIsSkippedWithoutBreakingTheRequest() {
        registry.addAdvisor(OWNER, brokenAdvisor());
        registry.addAdvisor(OWNER, countingAdvisor());

        int before = calls.get();
        String value = siteSettingsService.get("aop.test.key", "still-works");

        assertEquals("still-works", value, "坏 Advisor 不应让调用失败");
        assertEquals(before + 1, calls.get(), "同一个插件里正常的 Advisor 仍然生效");
    }

    @Test
    void infrastructureBeansAreNotProxied() {
        registry.addAdvisor(OWNER, countingAdvisor());

        assertFalse(AopUtils.isAopProxy(messageSource), "框架 Bean（MessageSource）不代理");
        assertFalse(AopUtils.isAopProxy(pluginProperties),
                "@ConfigurationProperties / @Bean 产出的 Bean 不代理");
    }

    /**
     * 回归（严重）：Servlet 容器 Bean 绝不能被代理。
     * <p>
     * {@code GenericFilterBean.init()} 是 <b>final</b> 方法，CGLIB 覆盖不了它 —— 一旦把过滤器包成代理，
     * 容器会在**代理实例**上调用 {@code init()}，读到 {@code this.logger == null} 直接 NPE，
     * Tomcat 整个起不来（{@code @SpringBootTest} 用 MockMvc 不经 Tomcat，所以只有真实启动才发现得了）。
     */
    @Test
    void servletContainerBeansAreNeverProxied() {
        assertFalse(AopUtils.isAopProxy(responseBufferFilter), "Servlet Filter 不代理");
        assertFalse(AopUtils.isAopProxy(clientInfoFilter), "Servlet Filter 不代理");
        assertFalse(AopUtils.isAopProxy(setupFilter), "Servlet Filter 不代理");
        assertFalse(AopUtils.isAopProxy(staticResourceCharsetFilter), "Servlet Filter 不代理");
        assertFalse(aopBridge.isBridged("responseBufferFilter"));
        assertFalse(aopBridge.isBridged("clientInfoFilter"));
    }

    // ==================== 测试用 Advisor ====================

    /**
     * 测试专用 Bean（{@code @Bean} 产出的 Bean 不会被 AOP 桥代理，因此可以安全地用它观察
     * 「别的 Bean 拿到的 SiteSettingsService 引用是不是代理」）。
     */
    @TestConfiguration
    static class InjectedHolderConfiguration {

        @Bean
        InjectedHolder injectedHolder(SiteSettingsService siteSettingsService) {
            return new InjectedHolder(siteSettingsService);
        }
    }

    /** 记录构造器注入进来的 {@code SiteSettingsService} 引用。 */
    static class InjectedHolder {

        final SiteSettingsService injectedReference;

        InjectedHolder(SiteSettingsService injectedReference) {
            this.injectedReference = injectedReference;
        }
    }

    private Advisor countingAdvisor() {
        NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
        pointcut.addMethodName("get");
        pointcut.setClassFilter(type -> SiteSettingsService.class.isAssignableFrom(type));
        MethodInterceptor advice = invocation -> {
            calls.incrementAndGet();
            return invocation.proceed();
        };
        return new DefaultPointcutAdvisor(pointcut, advice);
    }

    /** pointcut 每次匹配都抛异常的「坏 Advisor」（模拟插件写错切面表达式）。 */
    private Advisor brokenAdvisor() {
        Pointcut pointcut = new Pointcut() {
            @Override
            public ClassFilter getClassFilter() {
                return ClassFilter.TRUE;
            }

            @Override
            public MethodMatcher getMethodMatcher() {
                return new MethodMatcher() {
                    @Override
                    public boolean matches(Method method, Class<?> targetClass) {
                        throw new IllegalStateException("测试用的坏 pointcut");
                    }

                    @Override
                    public boolean isRuntime() {
                        return false;
                    }

                    @Override
                    public boolean matches(Method method, Class<?> targetClass, Object... args) {
                        throw new IllegalStateException("测试用的坏 pointcut");
                    }
                };
            }
        };
        MethodInterceptor advice = invocation -> {
            throw new AssertionError("坏 Advisor 的通知不应被执行");
        };
        return new DefaultPointcutAdvisor(pointcut, advice);
    }
}
