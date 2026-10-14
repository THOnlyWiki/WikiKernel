package cc.thonly.wikikernel.plugin.depdemo.hook;

import cc.thonly.wikikernel.plugin.depdemo.service.DepDemoService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Pointcut;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DepDemoAopHook}：切面只命中 core 的 {@code SiteSettingsService#get}，并且只计数、不改行为。 */
class DepDemoAopHookTest {

    private static Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return SiteSettingsService.class.getMethod(name, parameterTypes);
    }

    @Test
    void pointcutMatchesOnlySiteSettingsGet() throws NoSuchMethodException {
        Pointcut pointcut = DepDemoAopHook.siteSettingsGetPointcut();

        Method get = method("get", String.class, String.class);
        Method getBoolean = method("getBoolean", String.class, boolean.class);

        assertTrue(pointcut.getClassFilter().matches(SiteSettingsService.class));
        assertTrue(pointcut.getMethodMatcher().matches(get, SiteSettingsService.class));
        assertFalse(pointcut.getMethodMatcher().matches(getBoolean, SiteSettingsService.class),
                "同名类型上的其它方法不该被命中");
        assertFalse(pointcut.getClassFilter().matches(String.class), "其它类型不该被命中");
    }

    @Test
    void pointcutMatchesOnlyPluginOwnBeanGreet() throws NoSuchMethodException {
        Pointcut pointcut = DepDemoAopHook.depDemoGreetPointcut();
        Method greet = DepDemoService.class.getMethod("greet", String.class);

        assertTrue(pointcut.getClassFilter().matches(DepDemoService.class));
        assertTrue(pointcut.getMethodMatcher().matches(greet, DepDemoService.class));
        assertFalse(pointcut.getClassFilter().matches(SiteSettingsService.class),
                "插件自己的切面不该命中 core / 其它类型");
    }

    @Test
    void hookExposesBothAdvisors() {
        assertEquals(2, new DepDemoAopHook().advisors().size(),
                "一个作用于 core Bean，一个作用于插件自己的 Bean");
    }

    @Test
    void adviceCountsAndProceeds() throws Throwable {
        MethodInterceptor advice = DepDemoAopHook.countingAdvice(DepDemoAopHook.GET_CALLS);
        Method get = method("get", String.class, String.class);
        MethodInvocation invocation = new MethodInvocation() {
            @Override
            public Method getMethod() {
                return get;
            }

            @Override
            public Object[] getArguments() {
                return new Object[]{"key", "默认值"};
            }

            @Override
            public Object proceed() {
                return "默认值";
            }

            @Override
            public Object getThis() {
                return this;
            }

            @Override
            public AccessibleObject getStaticPart() {
                return get;
            }
        };

        int before = DepDemoAopHook.GET_CALLS.get();
        Object result = advice.invoke(invocation);

        assertEquals("默认值", result, "切面必须原样返回被调用方法的返回值");
        assertEquals(before + 1, DepDemoAopHook.GET_CALLS.get());
    }
}
