package cc.thonly.wikikernel.plugin.aop;

import cc.thonly.wikikernel.common.plugin.PluginConstants;
import cc.thonly.wikikernel.config.PluginProperties;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.plugin.PluginSpringContextFactory;
import cc.thonly.wikikernel.plugin.PluginWebRegistrar;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.context.ServletConfigAware;
import org.springframework.web.context.ServletContextAware;
import org.springframework.web.filter.GenericFilterBean;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 插件 AOP 桥：给「可能被插件切面命中的」core Bean 套一层**桥接代理**
 * （{@link ProxyFactory} + {@link PluginAdviceBridge}），让在应用就绪后才加载的插件也能对
 * core / common 的 Bean 加切面，并且**对已经注入到其它 Bean 里的引用同样生效**
 * （这是本平台的关键点：core 全部使用构造器注入，插件加载时引用早已绑定）。
 * <p>
 * 只在下面三种情况之一启用（**默认安全**：宁可少代理，也不能让应用起不来）：
 * <ul>
 *     <li>{@code wikikernel.plugins.aop-bridge=auto}（默认）：**有插件在描述文件里声明
 *         {@code plugin.aop=true}** 时才启用；</li>
 *     <li>{@code true}：强制开（给所有插件的 CoreAopHook 用）；</li>
 *     <li>{@code false}：关。</li>
 * </ul>
 * 代理范围（「不要代理一切」）：
 * <ul>
 *     <li>仅 {@code cc.thonly.wikikernel.*} 自己的**单例** Bean（插件子容器里另有一份，作用域是插件自己的包），
 *         且至少有一个可被 CGLIB 切中的 public 方法；</li>
 *     <li>跳过基础设施：Spring / Thymeleaf / MyBatis / JDBC 等框架 Bean、**Servlet 容器相关 Bean**
 *         （{@code Filter} / {@link GenericFilterBean} / {@code FilterRegistrationBean} / {@code Servlet} /
 *         {@code ServletContextInitializer} / {@code HandlerInterceptor} / {@code WebMvcConfigurer} /
 *         {@code ErrorController}）、数据源与事务管理器、各种 {@code *Aware} 基础设施、
 *         {@code ApplicationListener}、{@code BeanPostProcessor} / {@code BeanFactoryPostProcessor} /
 *         {@link FactoryBean} / {@link Advisor} / {@link Pointcut}、{@code @Configuration} 与
 *         {@code @ConfigurationProperties}、{@code @Bean} 工厂方法产出的 Bean、插件平台自身的 Bean；</li>
 *     <li>跳过已经是 AOP / JDK 代理的 Bean（{@code @Transactional}、MyBatis Mapper 等）；</li>
 *     <li>跳过 {@code final} / 非 public 类，以及**类或其父类里有非 private、非 static 的 final 方法**的类
 *         ——CGLIB 覆盖不了 final 方法，它会直接在**代理实例**上执行，而代理实例的字段是空的
 *         （踩过的坑：{@code GenericFilterBean.init()} 是 final，读到 {@code this.logger == null} → Tomcat 起不来）；</li>
 *     <li>代理创建失败只记 WARN 并跳过该 Bean，绝不影响启动；</li>
 *     <li>**没有命中任何插件切面的方法只多一次判断**，直接调用原对象，行为不变。</li>
 * </ul>
 * 为什么不是「创建时逐个方法判断是否命中」：{@code ProxyFactory} / CGLIB 在**代理创建时**就为每个方法
 * 固定了回调（{@code CglibAopProxy.ProxyCallbackFilter} 会按当时的通知链把方法编译成「直接调用目标」），
 * 而插件是在 core Bean 创建完成之后才加载的 —— 创建时链为空的方法，之后注册的 Advisor 永远不会生效。
 * 因此这里收窄**Bean 范围**，把**方法匹配**放到每次调用时做（见 {@link PluginAdviceBridge}）。
 */
@Component
public class PluginAopBridge implements SmartInstantiationAwareBeanPostProcessor, BeanFactoryAware {

    /**
     * 配置键：{@code auto}（默认）/ {@code true} / {@code false}。
     * <p>
     * {@code auto} = **只有插件在描述文件里声明 {@code plugin.aop=true}** 时才启用桥接
     * （默认安全：宁可少代理，也不能让应用起不来）；{@code true} / {@code false} = 强制开 / 关。
     */
    public static final String BRIDGE_PROPERTY = "wikikernel.plugins.aop-bridge";

    private static final Logger log = LoggerFactory.getLogger(PluginAopBridge.class);

    /** core 作用域：只代理本仓库自己的 Bean。 */
    private static final String CORE_PACKAGE_PREFIX = "cc.thonly.wikikernel.";

    /** 插件平台自身的基础设施 Bean：不代理，避免 AOP 桥自己调用自己时产生递归。 */
    private static final Set<Class<?>> PLATFORM_TYPES = Set.of(
            PluginManager.class, PluginAopBridge.class, PluginAopRegistry.class,
            PluginSpringContextFactory.class, PluginWebRegistrar.class, PluginProperties.class);

    /**
     * 框架 / Web 基础设施类型：一律不代理。
     * <p>
     * 这些 Bean 由容器按生命周期回调（{@code init} / {@code doFilter} / {@code afterPropertiesSet} 等）、
     * 或由 MVC 在启动时缓存实例，代理它们轻则无意义、重则直接让容器起不来
     * （踩过的坑：{@code GenericFilterBean.init} 是 final，跑到代理实例上读 {@code this.logger} → NPE）。
     */
    private static final Set<Class<?>> INFRASTRUCTURE_TYPES = Set.of(
            Filter.class, GenericFilterBean.class, FilterRegistrationBean.class,
            Servlet.class, ServletRegistrationBean.class, ServletContextInitializer.class,
            HandlerInterceptor.class, WebMvcConfigurer.class,
            javax.sql.DataSource.class, PlatformTransactionManager.class, TransactionManager.class,
            ApplicationContextAware.class, ServletContextAware.class, ServletConfigAware.class,
            BeanFactoryAware.class, BeanClassLoaderAware.class, ResourceLoaderAware.class,
            EnvironmentAware.class, MessageSourceAware.class, ApplicationEventPublisherAware.class);

    private final Environment environment;
    private final ObjectProvider<PluginAopRegistry> registryProvider;
    /** 插件子容器作用域：直接持有 registry（子容器不参与 core 的 Bean 生命周期）。 */
    private final PluginAopRegistry registry;
    private final String packagePrefix;

    private final Map<String, ProxyHolder> proxies = new ConcurrentHashMap<>();
    private final Map<Class<?>, Boolean> infrastructureClasses = new ConcurrentHashMap<>();

    private volatile Boolean enabled;
    private BeanFactory beanFactory;

    /** core 作用域（Spring 注入）：包前缀 = 本仓库，启用与否按 {@link #BRIDGE_PROPERTY} + 插件是否存在决定。 */
    @Autowired
    public PluginAopBridge(Environment environment, ObjectProvider<PluginAopRegistry> registryProvider) {
        this.environment = environment;
        this.registryProvider = registryProvider;
        this.registry = null;
        this.packagePrefix = CORE_PACKAGE_PREFIX;
    }

    /** 插件子容器作用域：作用于插件自己的包，registry 由 core 传入（插件卸载后自动失效）。 */
    private PluginAopBridge(PluginAopRegistry registry, String packagePrefix, boolean enabled) {
        this.environment = null;
        this.registryProvider = null;
        this.registry = registry;
        this.packagePrefix = packagePrefix;
        this.enabled = enabled;
    }

    /**
     * 给插件子容器创建一份 AOP 桥（由 {@link PluginSpringContextFactory} 注册进子容器）：
     * 插件在 {@code onStart} 里注册的 Advisor 也能命中它**自己插件里的 Bean**。
     *
     * @param prefix 插件入口类所在包（只代理这个包及其子包的 Bean）
     */
    public static PluginAopBridge forPluginContext(PluginAopRegistry registry, String prefix, boolean enabled) {
        return new PluginAopBridge(registry, prefix, enabled);
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    /**
     * 插件全部启动后（{@code PluginManager} 的监听器排在最前，这里排最后）：
     * 如果有插件注册了 {@code CoreAopHook} 但桥没启用，给一条明确的 WARN，告诉运维怎么开。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void warnIfPluginsWantAopButBridgeIsOff() {
        if (bridgingEnabled()) {
            return;
        }
        PluginAopRegistry current = currentRegistry();
        if (current == null || current.advisors().isEmpty()) {
            return;
        }
        log.warn("有插件注册了 CoreAopHook（当前 {} 个 Advisor），但插件 AOP 桥未启用（{} = {}），切面不会生效："
                        + "请在插件描述文件里加 plugin.aop=true，或用启动参数 --{}=true 全局启用",
                current.advisors().size(), BRIDGE_PROPERTY,
                this.environment == null ? "auto" : this.environment.getProperty(BRIDGE_PROPERTY, "auto"),
                BRIDGE_PROPERTY);
    }

    @Override
    public Object getEarlyBeanReference(Object bean, String beanName) {
        return proxyIfEligible(bean, beanName);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        return proxyIfEligible(bean, beanName);
    }

    // ==================== 内部实现 ====================

    private Object proxyIfEligible(Object bean, String beanName) {
        if (bean == null || beanName == null || !bridgingEnabled()) {
            return bean;
        }
        ProxyHolder existing = this.proxies.get(beanName);
        if (existing != null && existing.target() == bean) {
            // 循环依赖提前暴露时建过代理：必须复用同一个实例，否则 Spring 会报「注入了原始版本」
            return existing.proxy();
        }
        if (!eligible(bean, beanName)) {
            return bean;
        }
        Object proxy = createProxy(bean, beanName);
        return proxy == null ? bean : proxy;
    }

    private Object createProxy(Object bean, String beanName) {
        Class<?> type = bean.getClass();
        try {
            ProxyFactory factory = new ProxyFactory(bean);
            // 强制 CGLIB：这样按具体类注入的引用（core 的构造器注入基本都是具体类 / 接口）也能拿到代理
            factory.setProxyTargetClass(true);
            factory.addAdvisor(new PluginAdviceBridge(this::currentRegistry, bean));
            Object proxy = factory.getProxy(type.getClassLoader());
            this.proxies.put(beanName, new ProxyHolder(bean, proxy));
            log.debug("插件 AOP 桥已就绪: {} → {}", beanName, type.getName());
            return proxy;
        } catch (Throwable throwable) {
            if (throwable instanceof VirtualMachineError error) {
                throw error;
            }
            // 兜底：代理建不出来只记日志并跳过这个 Bean，绝不影响应用启动
            log.warn("无法为 Bean {}（{}）建立插件 AOP 代理，已跳过该 Bean: {}",
                    beanName, type.getName(), throwable.toString());
            return null;
        }
    }

    /** 是否值得代理（避免代理一切：只处理本仓库 / 本插件自己的业务 Bean，且必须能被安全代理）。 */
    private boolean eligible(Object bean, String beanName) {
        Class<?> type = bean.getClass();
        int modifiers = type.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isFinal(modifiers) || Modifier.isAbstract(modifiers)) {
            return false;
        }
        if (type.isInterface() || type.isEnum() || type.isAnnotation() || type.isArray() || type.isPrimitive()) {
            return false;
        }
        // 已经是 AOP / JDK 代理（@Transactional、MyBatis Mapper 等）：不重复包装
        if (Proxy.isProxyClass(type) || AopUtils.isAopProxy(bean)) {
            return false;
        }
        // 基础设施 Bean
        if (bean instanceof BeanPostProcessor || bean instanceof BeanFactoryPostProcessor
                || bean instanceof FactoryBean<?> || bean instanceof Advisor || bean instanceof Pointcut
                || bean instanceof ApplicationContext || bean instanceof Environment
                || bean instanceof ResourceLoader || bean instanceof ApplicationListener) {
            return false;
        }
        if (PLATFORM_TYPES.stream().anyMatch(candidate -> candidate.isInstance(bean))) {
            return false;
        }
        if (INFRASTRUCTURE_TYPES.stream().anyMatch(candidate -> candidate.isInstance(bean))) {
            return false;
        }
        if (beanName.startsWith("scopedTarget.") || beanName.startsWith("org.springframework.")) {
            return false;
        }
        if (isInfrastructureClass(type)) {
            return false;
        }
        // @Bean 工厂方法产出的 Bean（core 里基本都是 DataSource / FilterRegistrationBean / LogType 之类的基础设施）
        // 与手工注册的单例一并跳过，把代理面收窄到组件扫描出来的业务 Bean
        if (producedByBeanMethod(beanName)) {
            return false;
        }
        // 只代理目标包里的类：core = 本仓库自己的代码；插件子容器 = 插件自己的包
        if (this.packagePrefix != null && !type.getName().startsWith(this.packagePrefix)) {
            return false;
        }
        if (!hasProxyableMethod(type)) {
            // 「只对可能有匹配方法的单例 Bean 代理」：至少要有一个可以被 CGLIB 切中的 public 实例方法
            return false;
        }
        // 关键安全线：类（或其父类）里只要有一个**非 private、非 static 的 final 方法**，就不要代理 ——
        // CGLIB 覆盖不了 final 方法，它会直接在**代理实例**上执行，而代理实例的字段是空的
        // （典型事故：Servlet Filter 的 final init() 里读 this.logger → NPE → Tomcat 起不来）。
        return !hasNonPrivateFinalMethod(type);
    }

    /** 是否至少有一个 public、非 static、非 final 的实例方法（{@code Object} 的方法不算）。 */
    private static boolean hasProxyableMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            int modifiers = method.getModifiers();
            if (Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers)) {
                return true;
            }
        }
        return false;
    }

    /** 类或其父类里是否有「非 private、非 static 的 final 方法」（CGLIB 无法覆盖，会导致状态不一致）。 */
    private static boolean hasNonPrivateFinalMethod(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                int modifiers = method.getModifiers();
                if (Modifier.isFinal(modifiers) && !Modifier.isPrivate(modifiers) && !Modifier.isStatic(modifiers)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 是否由 {@code @Bean} 工厂方法产出（或没有 BeanDefinition 的手工单例）——这类 Bean 不代理。 */
    private boolean producedByBeanMethod(String beanName) {
        BeanFactory factory = this.beanFactory;
        if (factory == null) {
            return false;
        }
        try {
            if (factory instanceof ConfigurableListableBeanFactory listable
                    && listable.containsBeanDefinition(beanName)) {
                return listable.getBeanDefinition(beanName).getFactoryBeanName() != null;
            }
            return true;
        } catch (RuntimeException exception) {
            return true;
        }
    }

    /** {@code @Configuration}（含 {@code @Bean} 工厂）与 {@code @ConfigurationProperties} 属于基础设施。 */
    private boolean isInfrastructureClass(Class<?> type) {
        return this.infrastructureClasses.computeIfAbsent(type, candidate ->
                AnnotatedElementUtils.hasAnnotation(candidate, Configuration.class)
                        || AnnotatedElementUtils.hasAnnotation(candidate, ConfigurationProperties.class)
                        || ClassUtils.isCglibProxyClass(candidate));
    }

    private boolean bridgingEnabled() {
        Boolean cached = this.enabled;
        if (cached != null) {
            return cached;
        }
        String raw = this.environment == null ? "auto" : this.environment.getProperty(BRIDGE_PROPERTY, "auto");
        String value = raw == null ? "auto" : raw.trim().toLowerCase(Locale.ROOT);
        boolean decided = switch (value) {
            case "false", "off", "no", "0" -> false;
            case "true", "on", "yes", "1" -> true;
            default -> pluginDeclaresAop();
        };
        this.enabled = decided;
        log.info("插件 AOP 桥{}（{} = {}）", decided ? "已启用" : "已关闭", BRIDGE_PROPERTY, value);
        return decided;
    }

    /**
     * 是否有插件**显式声明**需要 AOP：扫描插件目录里每个 jar 的
     * {@code META-INF/wiki-plugin.properties} 看有没有 {@code plugin.aop=true}。
     * <p>
     * 为什么默认不「有插件就启用」：桥接代理要在 Bean 创建阶段建立（那时插件还没加载、不知道会声明什么 pointcut），
     * 一旦代理到不该代理的框架 Bean（Servlet Filter 的 final {@code init()} 等）就会让应用起不来。
     * 因此默认安全：**只有声明了 {@code plugin.aop=true} 的插件存在时才自动启用**；
     * 也可以显式设置 {@code wikikernel.plugins.aop-bridge=true} 为所有插件打开。
     */
    private boolean pluginDeclaresAop() {
        String directory = this.environment.getProperty("wikikernel.plugins.dir", "plugins");
        Path path = Path.of(directory).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            return false;
        }
        List<Path> jars;
        try (Stream<Path> files = Files.list(path)) {
            jars = files.filter(file -> file.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException exception) {
            log.warn("扫描插件目录失败，插件 AOP 桥按关闭处理: {}", path, exception);
            return false;
        }
        for (Path jar : jars) {
            if (declaresAop(jar)) {
                log.info("插件 {} 声明了 plugin.aop=true，插件 AOP 桥自动启用", jar.getFileName());
                return true;
            }
        }
        return false;
    }

    /** 读插件 jar 描述文件里的 {@code plugin.aop}。 */
    private boolean declaresAop(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(PluginConstants.DESCRIPTOR_LOCATION);
            if (entry == null) {
                return false;
            }
            Properties descriptor = new Properties();
            try (InputStream input = jarFile.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                descriptor.load(reader);
            }
            String value = descriptor.getProperty(PluginConstants.AOP, "").trim().toLowerCase(Locale.ROOT);
            return value.equals("true") || value.equals("1") || value.equals("yes") || value.equals("on");
        } catch (IOException | RuntimeException exception) {
            log.debug("读取插件描述文件失败（按未声明 AOP 处理）: {}", jar, exception);
            return false;
        }
    }

    /** 供桥接切面解析登记处（core 作用域用 ObjectProvider，避免 BPP 阶段提前创建 PluginManager）。 */
    private PluginAopRegistry currentRegistry() {
        if (this.registry != null) {
            return this.registry;
        }
        return this.registryProvider == null ? null : this.registryProvider.getIfAvailable();
    }

    /** 已建立的代理数量（诊断 / 测试用）。 */
    public int proxyCount() {
        return this.proxies.size();
    }

    /** 某个 Bean 是否已经套了桥接代理。 */
    public boolean isBridged(String beanName) {
        return this.proxies.containsKey(beanName);
    }

    /** 原始 Bean 与它的桥接代理（同名的必须是同一个代理实例，否则 Spring 会报「注入了原始版本」）。 */
    private record ProxyHolder(Object target, Object proxy) {
    }
}
