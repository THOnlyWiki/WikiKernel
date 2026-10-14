package cc.thonly.wikikernel.plugin;

import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.plugin.aop.PluginAopBridge;
import cc.thonly.wikikernel.plugin.aop.PluginAopRegistry;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 为每个插件创建一个 Spring 子容器（父容器是 core 的主容器）。
 * <p>
 * 这样插件里的 {@code @Component}/{@code @Service}/{@code @Configuration}/{@code @Bean}/
 * {@code @Autowired}/{@code @Value}/{@code @ConfigurationProperties} 全部生效，
 * 插件 Bean 也能注入 core 的 Bean（子容器可以访问父容器，反向不行）。
 * <p>
 * 插件 jar 用 {@code compileOnly} 引用 Spring，运行时由 core 提供，插件 jar 本身不打包 Spring。
 */
@Component
public class PluginSpringContextFactory {

    private final ApplicationContext applicationContext;
    private final ConfigurableEnvironment parentEnvironment;
    private final PluginAopRegistry aopRegistry;

    public PluginSpringContextFactory(ApplicationContext applicationContext, PluginAopRegistry aopRegistry) {
        this.applicationContext = applicationContext;
        this.parentEnvironment = (ConfigurableEnvironment) applicationContext.getEnvironment();
        this.aopRegistry = aopRegistry;
    }

    /**
     * @param pluginId      插件 id
     * @param entryClass    插件入口类
     * @param existing      已有实例（classpath 发现时复用）；为 null 时由 Spring 实例化入口类
     * @param classLoader   插件类加载器，可为 null（classpath 插件）
     * @param pluginContext 注册到子容器的 PluginContext
     */
    public AnnotationConfigApplicationContext create(String pluginId,
                                                     Class<? extends WikiPlugin> entryClass,
                                                     Supplier<WikiPlugin> existing,
                                                     ClassLoader classLoader,
                                                     PluginContext pluginContext) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setParent(applicationContext);
        context.setDisplayName("wiki-plugin-" + pluginId);
        if (classLoader != null) {
            context.setClassLoader(classLoader);
        }

        // 继承主应用的配置源，插件里 ${...} / @Value / @ConfigurationProperties 才能读到 application.yaml
        inheritPropertySources(context.getEnvironment());
        registerPlaceholderConfigurer(context);

        // 借助 Boot 的注册入口，让插件的 @ConfigurationProperties 生效
        ConfigurationPropertiesBindingPostProcessor.register(context);

        // PluginContext 作为 Bean 注册，插件里可直接 @Autowired PluginContext
        context.registerBean(PluginContext.class, () -> pluginContext);
        if (existing != null) {
            // classpath 发现的插件已经有实例，直接复用
            context.registerBean(WikiPlugin.class, existing);
        } else {
            // 由 Spring 实例化入口类，构造器注入 / Aware 回调都会生效
            context.register(entryClass);
        }

        scanPluginPackage(context, entryClass, classLoader);
        registerAopBridge(context, entryClass);
        context.refresh();
        return context;
    }

    /**
     * 给插件子容器也挂一份 AOP 桥：插件在 {@code onStart} 里注册的 {@code CoreAopHook}
     * 因此也能命中**插件自己的 Bean**（插件子容器不继承父容器的 BeanPostProcessor，必须单独注册）。
     * <p>
     * 开关与 core 一致（{@code wikikernel.plugins.aop-bridge}，默认 {@code auto} = 启用）；
     * 桥接代理只在切面命中时才走通知链，未命中时直接调用原对象。
     */
    private void registerAopBridge(AnnotationConfigApplicationContext context, Class<? extends WikiPlugin> entryClass) {
        if ("false".equalsIgnoreCase(environmentValue(PluginAopBridge.BRIDGE_PROPERTY, "auto"))) {
            return;
        }
        String packageName = entryClass.getPackageName();
        String prefix = packageName.isEmpty() ? null : packageName + ".";
        PluginAopBridge bridge = PluginAopBridge.forPluginContext(this.aopRegistry, prefix, true);
        // 手工注册的 BeanPostProcessor 不会收到 Aware 回调，这里显式把子容器的 BeanFactory 交给它
        bridge.setBeanFactory(context.getBeanFactory());
        context.getBeanFactory().addBeanPostProcessor(bridge);
    }

    private String environmentValue(String key, String fallback) {
        String value = this.parentEnvironment.getProperty(key, fallback);
        return value == null ? fallback : value.trim();
    }

    private void inheritPropertySources(ConfigurableEnvironment environment) {
        for (PropertySource<?> source : parentEnvironment.getPropertySources()) {
            if (!environment.getPropertySources().contains(source.getName())) {
                environment.getPropertySources().addLast(source);
            }
        }
    }

    private void registerPlaceholderConfigurer(AnnotationConfigApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();
        PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
        configurer.setEnvironment(environment);
        configurer.setPropertySources(environment.getPropertySources());
        context.addBeanFactoryPostProcessor(configurer);
    }

    /** 扫描入口类所在包（含子包），入口类本身由上面显式注册，扫描时排除，避免出现两个 WikiPlugin Bean。 */
    private void scanPluginPackage(AnnotationConfigApplicationContext context,
                                   Class<? extends WikiPlugin> entryClass,
                                   ClassLoader classLoader) {
        Package entryPackage = entryClass.getPackage();
        if (entryPackage == null || entryPackage.getName().isBlank()) {
            return;
        }
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(context, true);
        scanner.setResourceLoader(new PathMatchingResourcePatternResolver(
                classLoader != null ? classLoader : getClass().getClassLoader()));
        scanner.addExcludeFilter(new AssignableTypeFilter(entryClass));
        scanner.scan(entryPackage.getName());
    }
}
