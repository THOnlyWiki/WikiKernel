package cc.thonly.wikikernel.plugin.aop;

import cc.thonly.wikikernel.common.extension.CoreAopHook;
import cc.thonly.wikikernel.common.plugin.Extension;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginAopRegistry}：从插件 {@link CoreAopHook} 收集 Advisor、容错、撤销与快照刷新。
 * <p>
 * 纯单元测试（不启动 Spring 容器）：用 core 测试里惯用的 {@link PluginManager} 替身提供扩展点。
 */
class PluginAopRegistryTest {

    /** 只提供 CoreAopHook 的 PluginManager 替身（插件集合版本号可手动推进）。 */
    static final class FakePluginManager extends PluginManager {

        private final List<CoreAopHook> hooks = new ArrayList<>();
        private final AtomicLong generation = new AtomicLong();

        FakePluginManager() {
            super(null, null, null, null);
        }

        @Override
        public <T extends Extension> List<T> extensions(Class<T> extensionPoint) {
            if (extensionPoint != CoreAopHook.class) {
                return List.of();
            }
            List<T> result = new ArrayList<>(this.hooks.size());
            for (CoreAopHook hook : this.hooks) {
                result.add(extensionPoint.cast(hook));
            }
            return List.copyOf(result);
        }

        @Override
        public long generation() {
            return this.generation.get();
        }

        void addHook(CoreAopHook hook) {
            this.hooks.add(hook);
            this.generation.incrementAndGet();
        }

        void clearHooks() {
            this.hooks.clear();
            this.generation.incrementAndGet();
        }
    }

    private static CoreAopHook hook(List<Advisor> advisors) {
        return () -> advisors;
    }

    private static Advisor countingAdvisor(AtomicInteger counter) {
        NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
        pointcut.addMethodName("get");
        MethodInterceptor advice = invocation -> {
            counter.incrementAndGet();
            return invocation.proceed();
        };
        return new DefaultPointcutAdvisor(pointcut, advice);
    }

    private static Method getMethod() throws NoSuchMethodException {
        return FakeTarget.class.getMethod("get", String.class);
    }

    @Test
    void collectsAdvisorsFromPluginHooks() throws Exception {
        FakePluginManager manager = new FakePluginManager();
        AtomicInteger counter = new AtomicInteger();
        manager.addHook(hook(List.of(countingAdvisor(counter))));
        PluginAopRegistry registry = new PluginAopRegistry(manager);

        assertEquals(1, registry.advisors().size());
        assertFalse(registry.isEmpty());
        assertTrue(registry.matches(getMethod(), FakeTarget.class));
        assertEquals(1, registry.chainFor(getMethod(), FakeTarget.class).size());
    }

    @Test
    void pluginsWithoutAdvisorsAreIgnored() throws Exception {
        FakePluginManager manager = new FakePluginManager();
        manager.addHook(hook(List.of()));
        manager.addHook(hook(null));
        PluginAopRegistry registry = new PluginAopRegistry(manager);

        assertTrue(registry.isEmpty());
        assertFalse(registry.matches(getMethod(), FakeTarget.class));
        assertTrue(registry.chainFor(getMethod(), FakeTarget.class).isEmpty());
    }

    @Test
    void brokenHookIsSkippedAndDoesNotHideOtherHooks() throws Exception {
        FakePluginManager manager = new FakePluginManager();
        manager.addHook(() -> {
            throw new IllegalStateException("插件自己的 advisors() 抛异常");
        });
        manager.addHook(hook(List.of(countingAdvisor(new AtomicInteger()))));
        PluginAopRegistry registry = new PluginAopRegistry(manager);

        assertEquals(1, registry.advisors().size(), "坏插件的 Advisor 被跳过，好插件的仍然生效");
        assertTrue(registry.matches(getMethod(), FakeTarget.class));
    }

    @Test
    void advisorPointcutFailureIsSkipped() throws Exception {
        FakePluginManager manager = new FakePluginManager();
        AtomicInteger counter = new AtomicInteger();
        NameMatchMethodPointcut broken = new NameMatchMethodPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                throw new IllegalStateException("坏 pointcut");
            }
        };
        broken.addMethodName("get");
        manager.addHook(hook(List.of(new DefaultPointcutAdvisor(broken, (MethodInterceptor) invocation -> {
            counter.incrementAndGet();
            return invocation.proceed();
        }))));
        manager.addHook(hook(List.of(countingAdvisor(counter))));
        PluginAopRegistry registry = new PluginAopRegistry(manager);

        // 坏 Advisor 被跳过：匹配仍然为 true（来自好的那个），通知链只有好的那个
        assertTrue(registry.matches(getMethod(), FakeTarget.class));
        assertEquals(1, registry.chainFor(getMethod(), FakeTarget.class).size());
    }

    @Test
    void manualAdvisorsCanBeAddedAndRemoved() throws Exception {
        FakePluginManager manager = new FakePluginManager();
        PluginAopRegistry registry = new PluginAopRegistry(manager);

        registry.addAdvisor("test", countingAdvisor(new AtomicInteger()));
        assertTrue(registry.matches(getMethod(), FakeTarget.class));

        registry.removeOwner("test");
        assertFalse(registry.matches(getMethod(), FakeTarget.class));
        assertTrue(registry.isEmpty());
    }

    @Test
    void pluginUnloadRemovesItsAdvisors() throws Exception {
        FakePluginManager manager = new FakePluginManager();
        manager.addHook(hook(List.of(countingAdvisor(new AtomicInteger()))));
        PluginAopRegistry registry = new PluginAopRegistry(manager);
        assertTrue(registry.matches(getMethod(), FakeTarget.class));

        // PluginManager.stop() 之后 hooks 为空、generation 变化 → 快照自动重建为空
        manager.clearHooks();

        assertFalse(registry.matches(getMethod(), FakeTarget.class));
        assertTrue(registry.chainFor(getMethod(), FakeTarget.class).isEmpty());
    }

    /** 测试用的目标类型：{@code get(String)} 会被上面的 pointcut 命中。 */
    public static class FakeTarget {

        public String get(String key) {
            return key;
        }
    }
}
