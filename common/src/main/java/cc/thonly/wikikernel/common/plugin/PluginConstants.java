package cc.thonly.wikikernel.common.plugin;

/**
 * 插件约定常量。
 * <p>
 * 插件 jar 中推荐提供 {@link #DESCRIPTOR_LOCATION} 描述文件，core 优先读取它；
 * 没有描述文件时会回退到 {@code META-INF/services} + ServiceLoader 的发现方式。
 */
public final class PluginConstants {

    /** 插件描述文件在 jar 中的位置。 */
    public static final String DESCRIPTOR_LOCATION = "META-INF/wiki-plugin.properties";

    /** 插件 id（必填）。 */
    public static final String ID = "plugin.id";

    /** 插件名称。 */
    public static final String NAME = "plugin.name";

    /** 插件版本。 */
    public static final String VERSION = "plugin.version";

    /** 插件描述。 */
    public static final String DESCRIPTION = "plugin.description";

    /** 插件入口类（必须是 WikiPlugin 实现类）。 */
    public static final String MAIN_CLASS = "plugin.mainClass";

    /** 运行期依赖的其它插件 id（逗号分隔，可空）：被依赖的插件先启动，缺失 / 被禁用 / 启动失败时本插件跳过启动。 */
    public static final String REQUIRES = "plugin.requires";

    /**
     * core 版本约束（可空）：支持 {@code >=} / {@code >} / {@code <=} / {@code <} / {@code =} 与裸版本号；
     * 不满足时本插件跳过启动（core 版本取 {@code WikiKernelInfo.VERSION}）。
     */
    public static final String REQUIRES_CORE = "plugin.requiresCore";

    /**
     * 客户端（前端资源）依赖的其它插件 id（逗号分隔，可空）：只影响 {@code <head>} 注入顺序
     * （被依赖者在前）；缺失时页面留一条 HTML 注释并记 WARN，插件本身照常启动。
     */
    public static final String CLIENT_REQUIRES = "plugin.clientRequires";

    /**
     * 本插件的**功能开关**（站点设置键，可空；形如 {@code features.my-plugin}）。
     * <p>
     * 声明后 core 会在「站点设置 → 插件设置」里自动渲染一个复选框（安装向导的插件 tab 同理，
     * 写 {@code wikikernel.<开关键>}），插件自己用 {@code SiteConfig} 读同一个键——
     * **core 不需要为这个开关写任何代码**（插件没加载时开关也不出现）。
     */
    public static final String FEATURE_KEY = "plugin.featureKey";

    /** 功能开关的默认值（{@code true} / {@code false}；缺省 {@code true}）——仅在 yaml / 站点设置都没写时生效。 */
    public static final String FEATURE_DEFAULT = "plugin.featureDefault";

    /**
     * 是否声明「本插件要用 {@code CoreAopHook} 对 core 的 Bean 加切面」（{@code true} / 空）。
     * <p>
     * 声明后 core 的插件 AOP 桥在默认配置（{@code wikikernel.plugins.aop-bridge=auto}）下会自动启用。
     * 默认不启用是为了安全：桥接代理在 Bean 创建阶段建立，代理到框架 Bean 可能让容器起不来。
     */
    public static final String AOP = "plugin.aop";

    /** System.Logger 名称前缀。 */
    public static final String LOGGER_PREFIX = "wikikernel.plugin.";

    private PluginConstants() {
    }
}
