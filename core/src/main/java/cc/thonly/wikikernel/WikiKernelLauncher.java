package cc.thonly.wikikernel;

import cc.thonly.wikikernel.plugin.MixinSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;

/**
 * WikiKernel 自定义启动器。
 *
 * <p>真正的 Mixin 安装发生在 Java Agent 的 {@code premain} 阶段（见
 * {@code mixin-agent} 模块）。这个启动器负责在 Spring 容器创建前确认 Agent 状态，
 * 并给出可操作的诊断日志；它也能作为 Spring Boot 的 {@code Start-Class}。</p>
 */
public final class WikiKernelLauncher {

    private static final Logger log = LoggerFactory.getLogger(WikiKernelLauncher.class);

    private WikiKernelLauncher() {
    }

    public static void main(String[] args) {
        if (MixinSupport.isActive()) {
            log.info("Mixin agent 已启用；启动期 Mixin 插件: {}", MixinSupport.pluginIds());
        } else if (Boolean.getBoolean("wikikernel.mixin.agentLoaded")) {
            log.info("Mixin agent 已加载；未发现插件 Mixin 配置，本次不启用字节码转换");
        } else {
            log.warn("Mixin agent 未启用：插件中的 Mixin 不会生效。"
                    + "请通过 run.bat 启动，或使用 java -javaagent:<mixin-agent.jar> "
                    + "-cp <core.jar> org.springframework.boot.loader.launch.PropertiesLauncher");
        }
        SpringApplication.run(WikiKernelApplication.class, args);
    }
}
