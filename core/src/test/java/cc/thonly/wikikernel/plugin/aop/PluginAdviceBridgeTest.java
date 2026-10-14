package cc.thonly.wikikernel.plugin.aop;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桥接代理本身的机制测试（不启动 Spring 容器）：
 * {@code ProxyFactory(桥接切面) → 调用 → PluginAopRegistry 现取插件 Advisor → 通知链执行}。
 * <p>
 * 这条链路是「插件在应用就绪后才加载，却仍能切中已经注入到别处的 core Bean」的关键，
 * 因此单独用最小目标类验证，避免容器因素干扰。
 * <p>
 * 也固定住一个容易踩的坑：{@code ProxyFactory} / CGLIB 会在**代理创建时**按当时的通知链
 * 决定每个方法的回调，所以桥接切面必须「创建时就命中」（{@code Pointcut.TRUE}），
 * 否则之后注册的插件 Advisor 永远进不来。
 */
class PluginAdviceBridgeTest {

    /** 目标类：{@code get(String)} 会被下面的 pointcut 命中。 */
    public static class Target {

        public String get(String key) {
            return "value:" + key;
        }

        public String other() {
            return "other";
        }
    }

    private static Advisor countingAdvisor(AtomicInteger counter, Class<?> targetType) {
        NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
        pointcut.addMethodName("get");
        pointcut.setClassFilter(targetType::isAssignableFrom);
        MethodInterceptor advice = invocation -> {
            counter.incrementAndGet();
            return invocation.proceed();
        };
        return new DefaultPointcutAdvisor(pointcut, advice);
    }

    @Test
    void bridgeConsultsPluginAdvisorsOnEveryCall() {
        AtomicInteger counter = new AtomicInteger();
        PluginAopRegistry registry = new PluginAopRegistry(new PluginAopRegistryTest.FakePluginManager());
        Target target = new Target();
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new PluginAdviceBridge(() -> registry, target));
        Target proxy = (Target) factory.getProxy();

        // 代理创建时还没有任何插件切面：调用必须直通（这正是「插件加载前」的真实情形）
        assertEquals("value:a", proxy.get("a"));
        assertEquals(0, counter.get(), "没有切面时不应有通知");

        // 插件注册切面后（等价于 onStart 里注册 CoreAopHook），同一次调用立刻命中
        registry.addAdvisor("test", countingAdvisor(counter, Target.class));
        assertEquals("value:b", proxy.get("b"));
        assertEquals(1, counter.get(), "插件切面注册后应当命中");
        assertEquals("other", proxy.other());
        assertEquals(1, counter.get(), "未命中的方法不应进入通知链");

        // 撤销切面（等价于插件卸载）后回到直通
        registry.removeOwner("test");
        assertEquals("value:c", proxy.get("c"));
        assertEquals(1, counter.get());
    }

    /** 链路诊断：外层代理 → 桥接切面 → 登记处 → 内层通知链，逐段断言。 */
    @Test
    void diagnostics() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        PluginAopRegistry registry = new PluginAopRegistry(new PluginAopRegistryTest.FakePluginManager());
        Target target = new Target();
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        PluginAdviceBridge bridge = new PluginAdviceBridge(() -> registry, target);
        factory.addAdvisor(bridge);
        Object proxy = factory.getProxy();
        Method get = Target.class.getMethod("get", String.class);

        assertTrue(proxy instanceof Target);
        assertTrue(AopUtils.isAopProxy(proxy), "① 代理应当是 Spring AOP 代理");
        assertEquals(1, factory.getAdvisors().length, "② 外层代理持有的 Advisor 数");
        assertTrue(factory.getInterceptorsAndDynamicInterceptionAdvice(get, Target.class).size() == 1,
                "③ 桥接切面必须在创建时就命中（否则 CGLIB 会把方法固定成直通）");

        registry.addAdvisor("test", countingAdvisor(counter, Target.class));
        assertEquals(1, registry.advisors().size(), "④ 登记处 Advisor 数");
        assertTrue(registry.matches(get, Target.class), "⑤ 登记处匹配");
        assertEquals(1, registry.chainFor(get, Target.class).size(), "⑥ 内层通知链");

        assertEquals("value:a", ((Target) proxy).get("a"), "⑦ 调用返回值");
        assertEquals(1, counter.get(), "⑧ 通知是否执行");
    }

    @Test
    void bridgeWorksOnInterfaceTargetsAsWell() {
        AtomicInteger counter = new AtomicInteger();
        PluginAopRegistry registry = new PluginAopRegistry(new PluginAopRegistryTest.FakePluginManager());
        Target target = new Target();
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvisor(new PluginAdviceBridge(() -> registry, target));
        Target proxy = (Target) factory.getProxy();

        registry.addAdvisor("test", countingAdvisor(counter, Target.class));

        assertEquals("value:a", proxy.get("a"));
        assertEquals(1, counter.get());
        assertTrue(proxy instanceof Target);
    }

    /**
     * 目标类型没有接口时的 CGLIB 代理：{@code Object} 方法的语义必须与**普通 Spring AOP 代理**一致
     * （Spring 自己决定 {@code equals} / {@code hashCode} / {@code toString} 是落到目标还是落到配置），
     * {@code Advised} 方法则由代理配置回答。
     */
    @Test
    void bridgeKeepsObjectAndAdvisedSemantics() {
        PluginAopRegistry registry = new PluginAopRegistry(new PluginAopRegistryTest.FakePluginManager());
        Target target = new Target();
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new PluginAdviceBridge(() -> registry, target));
        Object proxy = factory.getProxy();

        Target plainTarget = new Target();
        ProxyFactory plainFactory = new ProxyFactory(plainTarget);
        plainFactory.setProxyTargetClass(true);
        plainFactory.addAdvisor(new DefaultPointcutAdvisor(Pointcut.TRUE, (MethodInterceptor) invocation -> {
            return invocation.proceed();
        }));
        Object plainProxy = plainFactory.getProxy();

        assertEquals(plainProxy.hashCode() == plainTarget.hashCode(), proxy.hashCode() == target.hashCode(),
                "hashCode 语义应当与普通 Spring AOP 代理一致");
        assertEquals(plainProxy.equals(plainTarget), proxy.equals(target),
                "equals 语义应当与普通 Spring AOP 代理一致");
        assertEquals(plainProxy.toString().equals(plainTarget.toString()),
                proxy.toString().equals(target.toString()),
                "toString 语义应当与普通 Spring AOP 代理一致");

        assertSame(Target.class, AopUtils.getTargetClass(proxy), "getTargetClass 由 Advised 配置回答");
        assertEquals(1, ((Advised) proxy).getAdvisors().length, "Advised 方法由配置回答");
        assertTrue(registry.advisors().isEmpty());
    }
}
