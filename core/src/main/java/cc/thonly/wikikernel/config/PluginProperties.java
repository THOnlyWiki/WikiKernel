package cc.thonly.wikikernel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件配置，对应 application.yaml：
 *
 * <pre>
 * wikikernel:
 *   plugins:
 *     dir: plugins               # 运行时扫描的插件 jar 目录（相对启动时的工作目录）
 *     runtime-dir: run/.plugins  # 运行时副本目录（加载的是拷贝，源 jar 不被锁定）
 *     classpath-scan: true       # 是否同时用 ServiceLoader 扫描 classpath 上的插件
 *     enabled: []                # 白名单，为空表示启用扫描到的全部插件
 *     aop-bridge: auto           # 插件 AOP 桥：auto（默认，装了插件才给 core Bean 套桥接代理）/ true / false
 *     settings:                  # 各插件的私有配置，键为插件 id
 *       your-plugin:
 *         key: value
 * </pre>
 */
@ConfigurationProperties(prefix = "wikikernel.plugins")
public class PluginProperties {

    private String dir = "plugins";

    /**
     * 插件**运行时副本**目录（默认 {@code run/.plugins}，相对启动时的工作目录）。
     * <p>
     * 启动插件前会把 {@link #dir} 里的每个 jar 按内容哈希复制到这里，**加载的是副本**：
     * 源目录里的 jar 不被 JVM 锁定，服务器所有者可以随时替换 / 删除 / 新增插件文件
     * （Windows 上不会提示「文件被另一个程序占用」），再调用 {@code POST /api/plugins/reload}
     * 动态装卸。同一内容的副本会复用；旧副本尽力清理（被其它实例占用时跳过）。
     */
    private String runtimeDir = "run/.plugins";

    private boolean classpathScan = true;
    private List<String> enabled = new ArrayList<>();
    private String aopBridge = "auto";
    private Map<String, Map<String, String>> settings = new LinkedHashMap<>();

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public String getRuntimeDir() {
        return runtimeDir;
    }

    public void setRuntimeDir(String runtimeDir) {
        this.runtimeDir = runtimeDir;
    }

    public boolean isClasspathScan() {
        return classpathScan;
    }

    public void setClasspathScan(boolean classpathScan) {
        this.classpathScan = classpathScan;
    }

    public List<String> getEnabled() {
        return enabled;
    }

    public void setEnabled(List<String> enabled) {
        this.enabled = enabled;
    }

    /**
     * 插件 AOP 桥开关（{@code wikikernel.plugins.aop-bridge}）：
     * {@code auto}（默认）= 只有插件在描述文件里声明了 {@code plugin.aop=true} 时才给 core Bean
     * 套桥接代理（默认安全：宁可少代理，也不能让应用起不来）；{@code true} / {@code false} = 强制开 / 关。
     * <p>
     * 注意：真正的读取发生在 Bean 创建阶段（{@code core/plugin/aop/PluginAopBridge} 直接读 Environment），
     * 这里保留字段是为了配置元数据、{@code /init} 与日志排查。开关打开后，框架 / Web 基础设施 Bean
     * （Filter、Servlet、HandlerInterceptor、数据源、事务管理器、带 final 方法的类等）仍然不会被代理。
     */
    public String getAopBridge() {
        return aopBridge;
    }

    public void setAopBridge(String aopBridge) {
        this.aopBridge = aopBridge;
    }

    public Map<String, Map<String, String>> getSettings() {
        return settings;
    }

    public void setSettings(Map<String, Map<String, String>> settings) {
        this.settings = settings;
    }
}
