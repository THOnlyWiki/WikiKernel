package cc.thonly.wikikernel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TypeScript 服务端编译配置（LocalSettings.yaml 的 {@code wikikernel.typescript.*}）。
 * <p>
 * 编译在 JVM 内完成（GraalJS 跑官方 {@code typescript.js}，自包含、不依赖主机安装 Node）；
 * 开关（是否编译 / 是否保存时编译）在 {@code features.typescript*} 里，可在站点设置里改。
 *
 * <pre>
 * wikikernel:
 *   typescript:
 *     target: ES2020        # 输出语言级别
 *     module: ESNext        # 模块形态（页面脚本一般写 ESNext；经典脚本可用 None）
 *     jsx: react            # .tsx 的 JSX 形态：react / preserve（空 = 依扩展名自动）
 *     timeout-seconds: 15   # 单次编译超时（超时即中斷编译器并重建引擎）
 *     cache-limit: 200      # 编译产物缓存的最大行数（按页面保留最新一条）
 * </pre>
 */
@ConfigurationProperties(prefix = "wikikernel.typescript")
public class TypeScriptProperties {

    /** 输出语言级别（TS 的 {@code ScriptTarget} 名，如 ES5 / ES2020 / ESNext）。 */
    private String target = "ES2020";

    /** 模块形态（TS 的 {@code ModuleKind} 名，如 None / CommonJS / ESNext）。 */
    private String module = "ESNext";

    /** JSX 形态（{@code JsxEmit} 名，如 react / preserve）；空 = 只有 .tsx 时用 react。 */
    private String jsx = "";

    /** 单次编译超时（秒）；超时会中断引擎并重建，避免拖住请求线程。 */
    private int timeoutSeconds = 15;

    /** 编译器首次装载超时（秒）：要读入并求值 8.7 MB 的 typescript.js，与源码无关，给更宽的窗口。 */
    private int initTimeoutSeconds = 120;

    /** 编译器引擎池大小（并发编译数）；每个引擎是一份独立的 GraalJS 上下文，常驻约 150–400 MB 内存。 */
    private int poolSize = 1;

    /** 是否在启动后后台预热引擎（把首次 4 秒级的装载从请求路径挪走；默认开启）。 */
    private boolean warmup = true;

    /** 单次编译的源码大小上限（KB）：防止超大源码带来的解析内存 / 时间放大。 */
    private int maxSourceKilobytes = 256;

    /** 编译产物缓存上限（行数）；按「页面 + 目标」只保留最新版本，超出按时间清理。 */
    private int cacheLimit = 200;

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getJsx() {
        return jsx;
    }

    public void setJsx(String jsx) {
        this.jsx = jsx;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getInitTimeoutSeconds() {
        return initTimeoutSeconds;
    }

    public void setInitTimeoutSeconds(int initTimeoutSeconds) {
        this.initTimeoutSeconds = initTimeoutSeconds;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public boolean isWarmup() {
        return warmup;
    }

    public void setWarmup(boolean warmup) {
        this.warmup = warmup;
    }

    public int getMaxSourceKilobytes() {
        return maxSourceKilobytes;
    }

    public void setMaxSourceKilobytes(int maxSourceKilobytes) {
        this.maxSourceKilobytes = maxSourceKilobytes;
    }

    public int getCacheLimit() {
        return cacheLimit;
    }

    public void setCacheLimit(int cacheLimit) {
        this.cacheLimit = cacheLimit;
    }
}
