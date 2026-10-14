package cc.thonly.wikikernel.plugin.depdemo.hook;

import cc.thonly.wikikernel.common.extension.CoreAopHook;
import cc.thonly.wikikernel.plugin.depdemo.service.DepDemoService;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code CoreAopHook} 演示：对 **core 自己的 Bean** 加切面 ——
 * 统计 core 站点设置读取（{@code cc.thonly.wikikernel.service.SiteSettingsService#get(String, String)}）
 * 的调用次数。
 * <p>
 * 关键点：
 * <ul>
 *     <li>插件只依赖 {@code common}，不能 {@code import} core 的类，因此目标类型用**类名字符串**判断
 *         （{@link #SITE_SETTINGS_SERVICE}）；</li>
 *     <li>pointcut 用 Spring 原生实现（{@link NameMatchMethodPointcut} + ClassFilter）：
 *         core 不带 {@code aspectjweaver}，{@code @Aspect} / {@code execution(...)} 不可用；</li>
 *     <li>统计值放在静态字段 {@link #GET_CALLS} 上，core 侧测试通过插件类加载器反射读取
 *         （插件与 core 之间没有编译期依赖）。</li>
 * </ul>
 */
public class DepDemoAopHook implements CoreAopHook {

    /** core 的目标类型（用字符串而不是 import：插件不能依赖 core）。 */
    public static final String SITE_SETTINGS_SERVICE = "cc.thonly.wikikernel.service.SiteSettingsService";

    /** 被切面命中的 core 调用次数（演示计数器；线程安全）。 */
    public static final AtomicInteger GET_CALLS = new AtomicInteger();

    /** 被切面命中的**插件自己 Bean** 调用次数。 */
    public static final AtomicInteger GREET_CALLS = new AtomicInteger();

    private final List<Advisor> advisors = List.of(
            new DefaultPointcutAdvisor(siteSettingsGetPointcut(), countingAdvice(GET_CALLS)),
            new DefaultPointcutAdvisor(depDemoGreetPointcut(), countingAdvice(GREET_CALLS)));

    @Override
    public List<Advisor> advisors() {
        return this.advisors;
    }

    /** 只匹配 {@code SiteSettingsService#get(...)}（环绕：先计数，再调用原方法）。 */
    static Pointcut siteSettingsGetPointcut() {
        NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
        pointcut.addMethodName("get");
        pointcut.setClassFilter(DepDemoAopHook::isSiteSettingsService);
        return pointcut;
    }

    /** 只匹配本插件自己的 {@link DepDemoService#greet(String)}（同一个 Counter 机制，独立的计数器）。 */
    static Pointcut depDemoGreetPointcut() {
        NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
        pointcut.addMethodName("greet");
        pointcut.setClassFilter(DepDemoService.class::isAssignableFrom);
        return pointcut;
    }

    static MethodInterceptor countingAdvice(AtomicInteger counter) {
        return invocation -> {
            counter.incrementAndGet();
            return invocation.proceed();
        };
    }

    /** 类型（或它的接口 / 父类）是不是 core 的 {@code SiteSettingsService}。 */
    private static boolean isSiteSettingsService(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (SITE_SETTINGS_SERVICE.equals(current.getName())) {
                return true;
            }
            for (Class<?> implemented : current.getInterfaces()) {
                if (SITE_SETTINGS_SERVICE.equals(implemented.getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
