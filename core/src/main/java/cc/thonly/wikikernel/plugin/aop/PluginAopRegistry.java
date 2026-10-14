package cc.thonly.wikikernel.plugin.aop;

import cc.thonly.wikikernel.common.extension.CoreAopHook;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.AdvisedSupport;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件切面登记处：把插件 {@link CoreAopHook} 提供的 Advisor 汇总成一个**不可变快照**，
 * 并按「插件加载顺序 → 插件内声明顺序」排序。
 * <p>
 * 为什么需要它：
 * <ul>
 *     <li>插件在 {@code ApplicationReadyEvent} 之后才注册扩展点，此时 core Bean 早已创建完毕 ——
 *         {@code PluginAopBridge} 在 Bean 创建阶段套的是「桥接代理」，真正的 Advisor 由本类在调用时提供，
 *         所以插件切面对**已注入的引用**同样生效；</li>
 *     <li>插件卸载（{@code PluginManager.stop()}）后 {@link PluginManager#generation()} 变化，
 *         快照自动重建为空 → 代理变成直通，不需要重新注入引用；</li>
 *     <li>每个快照自带独立的 {@link AdvisedSupport}（方法缓存不跨快照），
 *         因此「加 / 删 Advisor」一定会立刻生效，不会读到过期的方法链缓存。</li>
 * </ul>
 * 容错：{@code CoreAopHook.advisors()} 抛异常只记 WARN 并跳过该插件；
 * 单个 Advisor 的 pointcut 匹配抛异常也只记 WARN 并跳过该 Advisor（每个 Advisor 只记一次，避免刷日志）。
 */
@Component
public class PluginAopRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginAopRegistry.class);

    /** 坏 Advisor 的兜底：类型一律不匹配。 */
    private static final ClassFilter NEVER_MATCHES = type -> false;

    private final ObjectProvider<PluginManager> pluginManagerProvider;
    private final PluginManager fixedPluginManager;
    /** 平台内部 / 测试直接登记的 Advisor（owner → advisors）。 */
    private final Map<String, List<Advisor>> registered = new ConcurrentHashMap<>();
    private final Set<Advisor> reportedFailures = ConcurrentHashMap.newKeySet();

    private volatile Snapshot snapshot;
    private volatile long seenGeneration = Long.MIN_VALUE;

    /**
     * Spring 注入用：{@link PluginManager} 用 {@link ObjectProvider} **延迟**获取，
     * 否则会和 {@code PluginManager → PluginSpringContextFactory → PluginAopRegistry} 构成构造器循环依赖
     * （Boot 默认禁止循环引用，会启动失败）。
     */
    @Autowired
    public PluginAopRegistry(ObjectProvider<PluginManager> pluginManagerProvider) {
        this.pluginManagerProvider = pluginManagerProvider;
        this.fixedPluginManager = null;
    }

    /** 单元测试用：直接绑定一个 {@link PluginManager} 替身。 */
    public PluginAopRegistry(PluginManager pluginManager) {
        this.pluginManagerProvider = null;
        this.fixedPluginManager = pluginManager;
    }

    /** 当前生效的 Advisor（不可变快照，顺序 = 插件加载顺序 → 插件内声明顺序 → 手动登记）。 */
    public List<Advisor> advisors() {
        return snapshot().advisors();
    }

    /** 是否还没有任何插件提供切面（没有任何切面时桥接代理只是一层直通）。 */
    public boolean isEmpty() {
        return advisors().isEmpty();
    }

    /** 登记一组 Advisor（owner 用于整体撤销；测试与平台内部使用）。 */
    public void addAdvisors(String owner, List<Advisor> advisors) {
        if (owner == null || advisors == null || advisors.isEmpty()) {
            return;
        }
        List<Advisor> valid = advisors.stream().filter(advisor -> advisor != null).toList();
        if (valid.isEmpty()) {
            return;
        }
        this.registered.compute(owner, (key, existing) -> {
            List<Advisor> merged = new ArrayList<>(existing == null ? List.of() : existing);
            merged.addAll(valid);
            return List.copyOf(merged);
        });
        rebuild();
    }

    /** 登记单个 Advisor。 */
    public void addAdvisor(String owner, Advisor advisor) {
        addAdvisors(owner, List.of(advisor));
    }

    /** 撤销某个 owner 的 Advisor（插件卸载 / 测试清理）。 */
    public void removeOwner(String owner) {
        if (owner == null || this.registered.remove(owner) == null) {
            return;
        }
        rebuild();
    }

    /** 强制重建快照（正常无需调用：插件集合变化会自动重建）。 */
    public void refresh() {
        rebuild();
    }

    /** 方法是否被任一 Advisor 的切面命中（pointcut 抛异常 → 记 WARN 并跳过该 Advisor）。 */
    public boolean matches(Method method, Class<?> targetClass) {
        for (Advisor advisor : advisors()) {
            if (!(advisor instanceof PointcutAdvisor pointcutAdvisor)) {
                // IntroductionAdvisor 之类是类型级通知，不参与方法匹配
                continue;
            }
            try {
                Pointcut pointcut = pointcutAdvisor.getPointcut();
                // 类过滤必须先判断：MethodMatcher.matches(...) 本身不看 ClassFilter
                // （Spring 内部由 StaticMethodMatcherPointcut.matches 负责，这里手动走一遍）
                if (!pointcut.getClassFilter().matches(targetClass)) {
                    continue;
                }
                if (pointcut.getMethodMatcher().matches(method, targetClass)) {
                    return true;
                }
            } catch (RuntimeException exception) {
                reportFailure(advisor, exception);
            }
        }
        return false;
    }

    /** 命中该方法的通知链（交给 {@code ReflectiveMethodInvocation} 执行）；构建失败时返回空链（直接放行）。 */
    public List<Object> chainFor(Method method, Class<?> targetClass) {
        try {
            return snapshot().advised().getInterceptorsAndDynamicInterceptionAdvice(method, targetClass);
        } catch (RuntimeException exception) {
            log.warn("构建插件切面调用链失败，本次调用直接放行: {}", method, exception);
            return List.of();
        }
    }

    // ==================== 内部实现 ====================

    /** 当前 {@link PluginManager}（延迟解析，可能为 null —— 例如容器还没就绪）。 */
    private PluginManager pluginManager() {
        if (this.fixedPluginManager != null) {
            return this.fixedPluginManager;
        }
        try {
            return this.pluginManagerProvider == null ? null : this.pluginManagerProvider.getIfAvailable();
        } catch (RuntimeException exception) {
            log.debug("解析 PluginManager 失败，本轮视为没有插件切面", exception);
            return null;
        }
    }

    private long generationOf(PluginManager manager) {
        return manager == null ? 0L : manager.generation();
    }

    private Snapshot snapshot() {
        Snapshot current = this.snapshot;
        long generation = generationOf(pluginManager());
        if (current != null && generation == this.seenGeneration) {
            return current;
        }
        synchronized (this) {
            long latest = generationOf(pluginManager());
            if (this.snapshot == null || latest != this.seenGeneration) {
                rebuild();
            }
            return this.snapshot;
        }
    }

    private void rebuild() {
        PluginManager manager = pluginManager();
        List<Advisor> advisors = new ArrayList<>();
        for (CoreAopHook hook : hooks(manager)) {
            try {
                List<Advisor> provided = hook.advisors();
                if (provided == null) {
                    continue;
                }
                for (Advisor advisor : provided) {
                    if (advisor != null) {
                        advisors.add(advisor);
                    }
                }
            } catch (RuntimeException exception) {
                log.warn("插件提供的 CoreAopHook 取 Advisor 失败，已跳过该插件: {}", hook.getClass().getName(), exception);
            }
        }
        // 手动登记的放最后：插件自己的切面优先
        Map<String, List<Advisor>> manual = new LinkedHashMap<>(this.registered);
        manual.values().forEach(advisors::addAll);

        AdvisedSupport advised = new AdvisedSupport();
        advised.addAdvisors(advisors.stream().map(this::sanitize).toList());
        synchronized (this) {
            this.snapshot = new Snapshot(List.copyOf(advisors), advised);
            this.seenGeneration = generationOf(manager);
        }
    }

    /**
     * 给 Advisor 的 pointcut 包一层容错：匹配抛异常时返回「不命中」并记一次 WARN。
     * <p>
     * 这样**一个坏 Advisor 不会影响别的 Advisor**（{@code DefaultAdvisorChainFactory} 是整体构建通知链的，
     * 不包一层的话它会因为一个坏 pointcut 让整条链构建失败）。
     */
    private Advisor sanitize(Advisor advisor) {
        if (!(advisor instanceof PointcutAdvisor pointcutAdvisor)) {
            return advisor;
        }
        Pointcut original = pointcutAdvisor.getPointcut();
        Pointcut safePointcut = new Pointcut() {
            @Override
            public ClassFilter getClassFilter() {
                try {
                    return original.getClassFilter();
                } catch (RuntimeException exception) {
                    reportFailure(advisor, exception);
                    return NEVER_MATCHES;
                }
            }

            @Override
            public MethodMatcher getMethodMatcher() {
                return new MethodMatcher() {
                    @Override
                    public boolean matches(Method method, Class<?> targetClass) {
                        try {
                            return original.getMethodMatcher().matches(method, targetClass);
                        } catch (RuntimeException exception) {
                            reportFailure(advisor, exception);
                            return false;
                        }
                    }

                    @Override
                    public boolean isRuntime() {
                        try {
                            return original.getMethodMatcher().isRuntime();
                        } catch (RuntimeException exception) {
                            reportFailure(advisor, exception);
                            return false;
                        }
                    }

                    @Override
                    public boolean matches(Method method, Class<?> targetClass, Object... args) {
                        try {
                            return original.getMethodMatcher().matches(method, targetClass, args);
                        } catch (RuntimeException exception) {
                            reportFailure(advisor, exception);
                            return false;
                        }
                    }
                };
            }
        };
        return new DefaultPointcutAdvisor(safePointcut, pointcutAdvisor.getAdvice());
    }

    private List<CoreAopHook> hooks(PluginManager manager) {
        if (manager == null) {
            return List.of();
        }
        try {
            return manager.extensions(CoreAopHook.class);
        } catch (RuntimeException exception) {
            log.warn("收集插件 CoreAopHook 失败，本轮视为没有切面", exception);
            return List.of();
        }
    }

    private void reportFailure(Advisor advisor, RuntimeException exception) {
        if (this.reportedFailures.add(advisor)) {
            log.warn("插件 Advisor 的 pointcut 匹配失败，已跳过该 Advisor: {}", advisor, exception);
        }
    }

    private record Snapshot(List<Advisor> advisors, AdvisedSupport advised) {

        private Snapshot {
            advisors = Collections.unmodifiableList(advisors);
        }
    }
}
