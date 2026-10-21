package cc.thonly.wikikernel.mixin.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * 真正挂到 {@link java.lang.instrument.Instrumentation} 上的 transformer。
 * Mixin 自身的 agent 只负责 hot-swap 元数据，这里负责首次类加载的 Mixin 应用。
 */
final class WikiKernelMixinTransformer implements ClassFileTransformer {

    private final WikiKernelMixinService service;

    WikiKernelMixinTransformer(WikiKernelMixinService service) {
        this.service = service;
    }

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null || classBeingRedefined != null) {
            return null;
        }
        String binaryName = className.replace('/', '.');
        if (skip(binaryName)) {
            return null;
        }
        if (Boolean.getBoolean("wikikernel.mixin.debug")) {
            System.err.println("[WikiKernel/Mixin:debug] transform " + binaryName
                    + " loader=" + (loader == null ? "bootstrap" : loader.getClass().getName()));
        }
        return service.transform(loader, className, classfileBuffer);
    }

    private static boolean skip(String binaryName) {
        // Mixin / ASM / Guava / agent 自身：避免转换基础设施类导致递归。
        if (binaryName.startsWith("org.spongepowered.")
                || binaryName.startsWith("org.objectweb.asm.")
                || binaryName.startsWith("com.google.")
                || binaryName.startsWith("net.fabricmc.")
                || binaryName.startsWith("com.llamalad7.mixinextras.")
                || binaryName.startsWith("org.apache.commons.lang3.")
                || binaryName.startsWith("cc.thonly.wikikernel.mixin.agent.")) {
            return true;
        }
        // Spring Boot 启动器需要在 Mixin 初始化前工作；修改它风险极高，且不属于业务类。
        if (binaryName.startsWith("org.springframework.boot.loader.")) {
            return true;
        }
        // JDK 类由 bootstrap loader 加载，插件 Mixin 的 helper 类通常无法解析；
        // 同时避免修改 java.base 导致 JVM 级故障。
        return binaryName.startsWith("java.")
                || binaryName.startsWith("javax.")
                || binaryName.startsWith("jdk.")
                || binaryName.startsWith("sun.")
                || binaryName.startsWith("com.sun.");
    }
}
