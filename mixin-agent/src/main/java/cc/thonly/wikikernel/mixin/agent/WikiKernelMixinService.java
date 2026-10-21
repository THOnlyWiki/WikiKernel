package cc.thonly.wikikernel.mixin.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.LoggerAdapterConsole;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面向 WikiKernel 的 Mixin 服务实现。
 *
 * <p>SpongePowered Mixin 0.8.7 默认只提供 LaunchWrapper / ModLauncher 服务；
 * 本项目直接使用 Java instrumentation，所以在这里提供最小但完整的
 * {@link IClassProvider} / {@link IClassBytecodeProvider}。</p>
 *
 * <p>当前正在被转换的类加载器通过 {@link ThreadLocal} 传入，Mixin 读取
 * 目标类 / Mixin 类时走该加载器；插件 Mixin 的资源另有启动期暂存 jar 兜底。</p>
 */
public final class WikiKernelMixinService extends MixinServiceAbstract
        implements IClassProvider, IClassBytecodeProvider, IClassTracker {

    private static final ContainerHandleVirtual PRIMARY_CONTAINER = new ContainerHandleVirtual("WikiKernel");
    private static final Set<String> INVALID_CLASSES = ConcurrentHashMap.newKeySet();

    private final ThreadLocal<ClassLoader> activeLoader = new ThreadLocal<>();

    private volatile IMixinTransformerFactory transformerFactory;
    private volatile IMixinTransformer transformer;

    @Override
    public String getName() {
        return "WikiKernel";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void offer(IMixinInternal internal) {
        if (internal instanceof IMixinTransformerFactory factory) {
            this.transformerFactory = factory;
        }
        super.offer(internal);
    }

    @Override
    public IClassProvider getClassProvider() {
        return this;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        // Mixin 不委托给其它 transformer；WikiKernel 的其它字节码逻辑由插件自己负责。
        return null;
    }

    @Override
    public IClassTracker getClassTracker() {
        return this;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    protected ILogger createLogger(String name) {
        LoggerAdapterConsole logger = new LoggerAdapterConsole(name);
        return Boolean.getBoolean("wikikernel.mixin.debug") ? logger.setDebugStream(System.err) : logger;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return List.of();
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return PRIMARY_CONTAINER;
    }

    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        return List.of();
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        InputStream staged = MixinResources.open(name);
        if (staged != null) {
            return staged;
        }
        InputStream direct = MixinResources.openEntryAsStream(name);
        if (direct != null) {
            return direct;
        }
        ClassLoader loader = currentLoader();
        InputStream input = loader == null ? null : loader.getResourceAsStream(stripLeadingSlash(name));
        if (input != null) {
            return input;
        }
        return ClassLoader.getSystemResourceAsStream(stripLeadingSlash(name));
    }

    @Override
    public CompatibilityLevel getMinCompatibilityLevel() {
        return CompatibilityLevel.JAVA_8;
    }

    @Override
    public CompatibilityLevel getMaxCompatibilityLevel() {
        return CompatibilityLevel.JAVA_21;
    }

    @Override
    public URL[] getClassPath() {
        return MixinResources.classPath();
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return findClass(name, false);
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        String binaryName = name.replace('/', '.');
        if (isMixinRuntimeClass(binaryName)) {
            return Class.forName(binaryName, initialize, WikiKernelMixinService.class.getClassLoader());
        }
        ClassLoader loader = currentLoader();
        if (loader != null) {
            try {
                return Class.forName(binaryName, initialize, loader);
            } catch (ClassNotFoundException ignored) {
                // 再尝试服务类加载器：插件可能引用 agent 附带的类型（通常只有扩展类会这样）
            }
        }
        return Class.forName(binaryName, initialize, WikiKernelMixinService.class.getClassLoader());
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, WikiKernelMixinService.class.getClassLoader());
    }

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException {
        return getClassNode(name, false, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException {
        return getClassNode(name, runTransformers, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags) throws ClassNotFoundException {
        String binaryName = name.replace('/', '.');
        byte[] bytes = null;
        // 优先使用当前目标类加载器（core / 具体插件），避免暂存目录里的同名类覆盖真实定义。
        ClassLoader loader = currentLoader();
        if (loader != null) {
            try (InputStream input = loader.getResourceAsStream(binaryName.replace('.', '/') + ".class")) {
                if (input != null) {
                    bytes = input.readAllBytes();
                }
            } catch (Exception ignored) {
                // 交给下面的暂存资源 / ClassNotFoundException
            }
        }
        if (bytes == null) {
            bytes = MixinResources.readClass(binaryName);
        }
        if (bytes == null) {
            try (InputStream input = ClassLoader.getSystemResourceAsStream(binaryName.replace('.', '/') + ".class")) {
                if (input != null) {
                    bytes = input.readAllBytes();
                }
            } catch (Exception ignored) {
                // 交给下面的 ClassNotFoundException
            }
        }
        if (bytes == null) {
            throw new ClassNotFoundException("The specified class '" + binaryName + "' was not found");
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, readerFlags);
        return node;
    }

    @Override
    public void registerInvalidClass(String className) {
        INVALID_CLASSES.add(className.replace('/', '.'));
    }

    @Override
    public boolean isClassLoaded(String className) {
        // 不支持无副作用的“已加载”探测；返回 false 可避免把正常加载误判为 too early。
        return false;
    }

    @Override
    public String getClassRestrictions(String className) {
        return INVALID_CLASSES.contains(className.replace('/', '.')) ? "invalid mixin class" : "";
    }

    /**
     * 转换一个类。由 Java Agent 的 ClassFileTransformer 调用。
     *
     * @return 新字节码，或 null（未改变）
     */
    byte[] transform(ClassLoader loader, String className, byte[] classBytes) {
        ClassLoader previous = activeLoader.get();
        activeLoader.set(loader == null ? ClassLoader.getSystemClassLoader() : loader);
        try {
            IMixinTransformer mixinTransformer = transformer();
            if (mixinTransformer == null) {
                return null;
            }
            String binaryName = className.replace('/', '.');
            byte[] transformed = mixinTransformer.transformClassBytes(binaryName, binaryName, classBytes);
            if (Boolean.getBoolean("wikikernel.mixin.debug")) {
                System.err.println("[WikiKernel/Mixin:debug] transform result " + binaryName
                        + " changed=" + (transformed != classBytes));
            }
            return transformed == classBytes ? null : transformed;
        } catch (Throwable throwable) {
            System.err.println("[WikiKernel/Mixin] 转换失败：" + className.replace('/', '.')
                    + "，" + throwable.getClass().getName() + ": " + throwable.getMessage());
            throwable.printStackTrace(System.err);
            return null;
        } finally {
            if (previous == null) {
                activeLoader.remove();
            } else {
                activeLoader.set(previous);
            }
        }
    }

    /**
     * 提前创建 Mixin transformer。
     * MixinExtras 启动时需要拿到 Mixin 已注册的 Extensions，不能在第一次类转换时才懒创建。
     */
    synchronized void ensureTransformer() {
        if (this.transformer != null) {
            return;
        }
        if (this.transformerFactory == null) {
            throw new IllegalStateException("Mixin transformer factory 尚未由 MixinBootstrap 提供");
        }
        this.transformer = this.transformerFactory.createTransformer();
    }

    private IMixinTransformer transformer() {
        IMixinTransformer current = this.transformer;
        if (current != null) {
            return current;
        }
        IMixinTransformerFactory factory = this.transformerFactory;
        if (factory == null) {
            return null;
        }
        synchronized (this) {
            if (this.transformer == null) {
                this.transformer = factory.createTransformer();
            }
            return this.transformer;
        }
    }

    private ClassLoader currentLoader() {
        ClassLoader loader = activeLoader.get();
        if (loader != null) {
            return loader;
        }
        loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : ClassLoader.getSystemClassLoader();
    }

    private static boolean isMixinRuntimeClass(String binaryName) {
        return binaryName.startsWith("org.spongepowered.")
                || binaryName.startsWith("org.objectweb.asm.")
                || binaryName.startsWith("com.google.")
                || binaryName.startsWith("net.fabricmc.")
                || binaryName.startsWith("com.llamalad7.mixinextras.")
                || binaryName.startsWith("org.apache.commons.lang3.");
    }

    private static String stripLeadingSlash(String name) {
        return name.startsWith("/") ? name.substring(1) : name;
    }
}
