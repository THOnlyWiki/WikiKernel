package cc.thonly.wikikernel.plugin.aop;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Pointcut;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.framework.ReflectiveMethodInvocation;
import org.springframework.aop.support.AopUtils;

import java.util.List;
import java.util.function.Supplier;

/**
 * 「桥接切面」：core 在 Bean 创建阶段把它放进代理，真正的插件 Advisor 在**每次调用时**
 * 从 {@link PluginAopRegistry} 现取。
 * <p>
 * 为什么不让 core Bean 直接用插件 Advisor 建代理：插件是在应用就绪（{@code ApplicationReadyEvent}）
 * 之后才加载的，而 core Bean 与其**构造器注入的引用**在那之前就创建好了。
 * <p>
 * 为什么点切面是 {@link Pointcut#TRUE}（而不是「调用时动态匹配」的活点切面）：
 * {@code ProxyFactory} / CGLIB 在**代理创建时**就为每个方法固定了回调
 * （{@code CglibAopProxy.ProxyCallbackFilter} 会按当时的通知链把方法编译成「直接调用目标」），
 * 所以「创建时链为空」的方法之后永远不会再进切面。这里让外层一定命中，
 * 真正的匹配放在 {@link #invoke} 里做：没有插件切面命中时只多一次判断，然后直接调用原对象。
 * <p>
 * 代理的**范围**仍然由 {@link PluginAopBridge} 的 eligibility 规则收窄（不代理一切）。
 */
final class PluginAdviceBridge implements PointcutAdvisor, MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PluginAdviceBridge.class);

    private final Supplier<PluginAopRegistry> registrySupplier;
    /** 原始 Bean（代理的目标）：{@link ReflectiveMethodInvocation} 末端的反射调用用它。 */
    private final Object target;
    private final Class<?> targetClass;

    private volatile PluginAopRegistry registry;

    PluginAdviceBridge(Supplier<PluginAopRegistry> registrySupplier, Object target) {
        this.registrySupplier = registrySupplier;
        this.target = target;
        this.targetClass = AopUtils.getTargetClass(target);
    }

    @Override
    public Pointcut getPointcut() {
        return Pointcut.TRUE;
    }

    @Override
    public Advice getAdvice() {
        return this;
    }

    @Override
    public boolean isPerInstance() {
        return true;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        PluginAopRegistry current = registry();
        if (current == null) {
            return invocation.proceed();
        }
        List<Object> chain;
        try {
            chain = current.chainFor(invocation.getMethod(), this.targetClass);
        } catch (RuntimeException exception) {
            log.warn("构建插件切面通知链失败，本次调用直接放行: {}", invocation.getMethod(), exception);
            return invocation.proceed();
        }
        if (chain.isEmpty()) {
            // 没有插件切面命中这个方法：行为与没有代理时完全一致
            return invocation.proceed();
        }
        Object proxy = invocation instanceof ProxyMethodInvocation proxyInvocation
                ? proxyInvocation.getProxy()
                : this.target;
        // ReflectiveMethodInvocation 的构造器是 protected：用匿名子类来构造通知链的调用对象
        MethodInvocation chained = new ReflectiveMethodInvocation(proxy, this.target, invocation.getMethod(),
                invocation.getArguments(), this.targetClass, chain) {
        };
        return chained.proceed();
    }

    /** 解析插件切面登记处（core 作用域用 ObjectProvider 延迟解析，插件子容器直接用注入的实例）。 */
    private PluginAopRegistry registry() {
        PluginAopRegistry current = this.registry;
        if (current != null) {
            return current;
        }
        try {
            current = this.registrySupplier == null ? null : this.registrySupplier.get();
        } catch (RuntimeException exception) {
            log.debug("解析插件切面登记处失败，本次调用按无切面处理", exception);
            return null;
        }
        this.registry = current;
        return current;
    }
}
