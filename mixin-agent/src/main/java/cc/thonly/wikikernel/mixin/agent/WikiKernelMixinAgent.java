package cc.thonly.wikikernel.mixin.agent;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.tools.agent.MixinAgent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

/**
 * WikiKernel Mixin Java Agent。
 *
 * <p>启动顺序：</p>
 * <ol>
 *     <li>扫描插件目录，暂存所有插件 jar，并识别其中的 Mixin 配置；</li>
 *     <li>初始化 SpongePowered Mixin，并注册插件配置；</li>
 *     <li>安装 {@link WikiKernelMixinTransformer}；</li>
 *     <li>把暂存目录写入 Boot {@code loader.path}，让 {@code PropertiesLauncher}
 *         在创建应用类加载器时把这些插件加入 classpath（Mixin helper 类因此能被
 *         core / 目标插件解析）。</li>
 * </ol>
 *
 * <p>Mixin 配置只在启动期读取；带 Mixin 的插件不支持热替换，插件源 jar 变化后
 * 需要重启应用（普通插件仍可动态装卸）。</p>
 */
public final class WikiKernelMixinAgent {

    private static final String DEFAULT_PLUGIN_DIR = "plugins";
    private static final String DEFAULT_STAGE_DIR = "run/.mixin-plugins";

    private WikiKernelMixinAgent() {
    }

    /**
     * 支持 attach 方式加载；正常生产启动仍使用 {@code -javaagent}。
     * attach 时 core 可能已经加载，已加载类不会被自动重转换，Mixin 只在之后加载的类上生效。
     */
    public static void agentmain(String arguments, Instrumentation instrumentation) {
        premain(arguments, instrumentation);
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        System.setProperty("wikikernel.mixin.agentLoaded", "true");
        AgentArguments agentArguments = AgentArguments.parse(arguments);
        Path base = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path pluginDirectory = resolve(base, System.getProperty("wikikernel.mixin.pluginDir",
                agentArguments.pluginDir(DEFAULT_PLUGIN_DIR)));
        Path stageDirectory = resolve(base, System.getProperty("wikikernel.mixin.stageDir",
                agentArguments.stageDir(DEFAULT_STAGE_DIR)));
        String previousLoaderPath = System.getProperty("loader.path");

        try {
            MixinPluginScanner.ScanResult scan = MixinPluginScanner.scan(pluginDirectory, stageDirectory);
            if (!scan.hasMixins()) {
                System.out.println("[WikiKernel/Mixin] 未发现插件 Mixin 配置，Mixin 转换器不启用。");
                System.setProperty("wikikernel.mixin.active", "false");
                return;
            }

            MixinResources.install(scan.resourceJars(), scan.configs());
            configureSystemProperties();

            // 安装 hot-swap 元数据 transformer；首次加载的转换由下面自己的 transformer 完成。
            MixinAgent.premain(arguments == null ? "" : arguments, instrumentation);

            // 必须在 Spring Boot 创建 LaunchedClassLoader / 加载 core 之前初始化。
            MixinBootstrap.init();
            gotoDefaultPhase();

            MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.SERVER);
            Object service = MixinService.getService();
            if (!(service instanceof WikiKernelMixinService wikiKernelService)) {
                throw new IllegalStateException("Mixin 服务类型异常: " + service.getClass().getName());
            }

            // MixinExtras 需要 Mixin 的 Extensions 已经存在；先显式创建 transformer，再初始化。
            wikiKernelService.ensureTransformer();
            try {
                MixinExtrasBootstrap.init();
                System.setProperty("wikikernel.mixinextras.active", "true");
                System.out.println("[WikiKernel/MixinExtras] 已启用 MixinExtras "
                        + MixinExtrasBootstrap.getVersion());
            } catch (Throwable throwable) {
                // MixinExtras 失败不应该拖垮普通 Mixin；使用 Extras 注解的插件会在转换日志里报错。
                System.setProperty("wikikernel.mixinextras.active", "false");
                System.err.println("[WikiKernel/MixinExtras] 初始化失败；普通 Mixin 仍可使用。");
                throwable.printStackTrace(System.err);
            }

            int registered = 0;
            for (MixinPluginScanner.MixinConfigEntry config : scan.configs()) {
                try {
                    Mixins.addConfiguration(config.resource());
                    registered++;
                } catch (Throwable throwable) {
                    System.err.println("[WikiKernel/Mixin] 注册配置失败：" + config.resource()
                            + "（插件 " + config.pluginId() + "），"
                            + throwable.getClass().getName() + ": " + throwable.getMessage());
                }
            }

            instrumentation.addTransformer(new WikiKernelMixinTransformer(wikiKernelService), true);

            installLoaderPath(scan.loaderJars());
            System.setProperty("wikikernel.mixin.active", "true");
            System.setProperty("wikikernel.mixin.plugins", String.join(",", scan.pluginIds()));
            System.setProperty("wikikernel.mixin.stageDir", stageDirectory.toString());
            System.out.println("[WikiKernel/Mixin] 已启用；插件 " + scan.pluginIds()
                    + "，配置 " + registered + " 个，暂存目录 " + stageDirectory);
        } catch (Throwable throwable) {
            System.err.println("[WikiKernel/Mixin] 初始化失败，服务将以未启用 Mixin 的方式启动。");
            throwable.printStackTrace(System.err);
            System.setProperty("wikikernel.mixin.active", "false");
            if (previousLoaderPath == null) {
                System.clearProperty("loader.path");
            } else {
                System.setProperty("loader.path", previousLoaderPath);
            }
        }
    }

    private static void configureSystemProperties() {
        System.setProperty("mixin.service", WikiKernelMixinService.class.getName());
        System.setProperty("mixin.env.disableRefMap", "true");
        System.setProperty("mixin.env.remapRefMap", "false");
        System.setProperty("mixin.env.compatLevel", "JAVA_21");
        System.setProperty("mixin.hotSwap", "true");
        if (Boolean.getBoolean("wikikernel.mixin.debug")) {
            System.setProperty("mixin.debug", "true");
        }
    }

    /**
     * Mixin 默认环境初始化在 PREINIT 阶段；独立启动器不经过 LaunchWrapper 的 phase 事件，
     * 因此显式切到 DEFAULT，确保随后注册的配置绑到实际运行阶段。
     */
    private static void gotoDefaultPhase() throws ReflectiveOperationException {
        Method method = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        method.setAccessible(true);
        method.invoke(null, MixinEnvironment.Phase.DEFAULT);
    }

    private static void installLoaderPath(List<Path> stagedJars) {
        String existing = System.getProperty("loader.path", "").trim();
        // 使用具体 jar 而不是整个目录：目录里可能残留被其它实例锁住的旧版本，
        // PropertiesLauncher 会把目录下所有 jar 都加入 classpath，造成类冲突。
        String staged = stagedJars.stream()
                .map(Path::toString)
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
        if (!staged.isEmpty()) {
            System.setProperty("loader.path", existing.isEmpty()
                    ? staged
                    : staged + File.pathSeparator + existing);
        }
    }

    private static Path resolve(Path base, String value) {
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : base.resolve(path).normalize();
    }

    private record AgentArguments(String pluginDir, String stageDir) {

        static AgentArguments parse(String arguments) {
            String pluginDir = null;
            String stageDir = null;
            if (arguments != null && !arguments.isBlank()) {
                for (String part : arguments.split("[,;]")) {
                    String trimmed = part.trim();
                    int separator = trimmed.indexOf('=');
                    if (separator <= 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, separator).trim();
                    String value = trimmed.substring(separator + 1).trim();
                    if ("pluginDir".equalsIgnoreCase(key)) {
                        pluginDir = value;
                    } else if ("stageDir".equalsIgnoreCase(key)) {
                        stageDir = value;
                    }
                }
            }
            return new AgentArguments(pluginDir, stageDir);
        }

        String pluginDir(String fallback) {
            return pluginDir == null || pluginDir.isBlank() ? fallback : pluginDir;
        }

        String stageDir(String fallback) {
            return stageDir == null || stageDir.isBlank() ? fallback : stageDir;
        }
    }
}
