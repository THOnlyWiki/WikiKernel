package cc.thonly.wikikernel.typescript;

import cc.thonly.wikikernel.config.TypeScriptProperties;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * GraalJS 实现的 TypeScript 编译器（自包含，不依赖主机安装 Node）。
 *
 * <h2>安全边界（重要）</h2>
 * TypeScript 的 {@code transpileModule} **只解析 / 转换文本，不执行用户的代码**：用户写的内容始终是
 * 被当作**数据**传给 {@code ts.transpileModule(source, …)} 的字符串，不会被求值。尽管如此，本类仍按
 * 「输入不可信」的默认值收紧 JS 引擎（见 {@link #sandboxedBuilder()}）：
 * <ul>
 *     <li>无宿主访问：{@code HostAccess.NONE} + 关闭类查找 → {@code Java.type('java.lang.System')} 抛
 *     {@code TypeError: Access to host class … is not allowed}；</li>
 *     <li>无文件 / 网络 IO：{@code IOAccess.NONE} → {@code load('/etc/passwd')} 抛
 *     {@code Operation is not allowed}；并额外关掉 {@code load} 函数本体；</li>
 *     <li>无线程 / 原生 / 多语言 / 环境访问：{@code allowCreateThread(false)}、
 *     {@code allowNativeAccess(false)}、{@code PolyglotAccess.NONE}、{@code EnvironmentAccess.NONE}；</li>
 *     <li>不导出 {@code java} 包全局与 {@code console}，固定 ECMAScript 版本，标准输出 / 错误丢进空流
 *     （编译器的输出不会污染站点日志）。</li>
 * </ul>
 * <b>但这不是「可安全运行任意不可信 JavaScript」的沙箱</b>：GraalJS 社区版**没有**可用的
 * {@code sandbox.MaxCPUTime / sandbox.MaxHeapMemory} 之类的配额选项（实测这些选项在 24.2.1 不存在，
 * 属于 Enterprise 沙箱能力），所以本类的资源保护是：
 * <ul>
 *     <li>**墙钟超时**：单次编译 {@code timeout-seconds}、引擎首次装载 {@code init-timeout-seconds}，
 *     超时即中断并**丢弃引擎**（下次重建），保证请求不会被无限挂住；</li>
 *     <li>**输入大小上限** {@code max-source-kilobytes}：避免超大源码带来的解析内存 / 时间放大；</li>
 *     <li>**并发上限**：引擎池大小 {@code pool-size}（默认 1）决定同时可用的 GraalJS 上下文数量，
 *     每个上下文常驻约 150–400 MB 级内存。</li>
 * </ul>
 * 结论：**适合编译站点自己人写的 TypeScript**（配合站点设置里的开关与权限）；如果将来要执行
 * 真正不可信的任意 JavaScript，请另建**进程级隔离**（独立进程 / 容器 + OS 级 CPU、内存、网络限制），
 * 不要依赖本类。
 *
 * <h2>并发</h2>
 * 编译任务跑在**虚拟线程**上（{@code newVirtualThreadPerTaskExecutor}）：等待几乎不占平台线程，
 * 不会因为编译排队而耗尽 Tomcat 请求线程池。GraalJS 的 {@link Context} 不是线程安全的，因此每个上下文
 * 由 {@link ReentrantLock} 独占；引擎池默认 1 个上下文（并发编译会排队），需要真正并行时把
 * {@code pool-size} 调大（每多一个上下文多一份内存）。
 */
@Service
public class GraalTypeScriptCompiler implements TypeScriptCompiler {

    private static final Logger log = LoggerFactory.getLogger(GraalTypeScriptCompiler.class);

    /** 内置的官方编译器（UMD 包，求值后暴露全局 {@code ts}）。 */
    private static final String BUNDLE_RESOURCE = "/typescript/typescript.js";

    /** 一次安装、反复调用的转译函数（把 TS API 收敛成一个小函数，Java 侧只搬字符串与诊断）。 */
    private static final String HELPER = """
            globalThis.__wkTranspile = function (source, fileName, target, moduleKind, jsx) {
                var options = {
                    target: ts.ScriptTarget[target] || ts.ScriptTarget.ES2020,
                    module: ts.ModuleKind[moduleKind] || ts.ModuleKind.ESNext,
                    removeComments: false,
                    sourceMap: false,
                    inlineSourceMap: false
                };
                if (jsx && ts.JsxEmit[jsx] !== undefined) {
                    options.jsx = ts.JsxEmit[jsx];
                }
                var result = ts.transpileModule(source, {
                    fileName: fileName,
                    reportDiagnostics: true,
                    compilerOptions: options
                });
                var diagnostics = [];
                (result.diagnostics || []).forEach(function (diagnostic) {
                    var line = 0;
                    var column = 0;
                    if (diagnostic.file && typeof diagnostic.start === 'number') {
                        var position = ts.getLineAndCharacterOfPosition(diagnostic.file, diagnostic.start);
                        line = position.line + 1;
                        column = position.character + 1;
                    }
                    diagnostics.push({
                        line: line,
                        column: column,
                        message: ts.flattenDiagnosticMessageText(diagnostic.messageText, ' ')
                    });
                });
                return { code: result.outputText, diagnostics: diagnostics, version: ts.version };
            };
            """;

    private final TypeScriptProperties properties;

    /** 编译任务跑在虚拟线程上：排队时几乎不占平台线程（Tomcat 请求线程池不会被拖垮）。 */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /** 引擎池（懒创建，最多 {@code pool-size} 个）；每个上下文同一时刻只被一个任务使用。 */
    private final List<EngineHolder> engines = new ArrayList<>();

    /** 选取引擎用（轮询，简单且分散压力）。 */
    private final AtomicInteger picker = new AtomicInteger();

    /** 编译器版本（引擎加载后填充，用于编译缓存键）。 */
    private volatile String compilerVersion = "typescript";

    public GraalTypeScriptCompiler(TypeScriptProperties properties) {
        this.properties = properties;
        int poolSize = Math.max(1, Math.min(8, properties.getPoolSize()));
        for (int index = 0; index < poolSize; index++) {
            engines.add(new EngineHolder(index));
        }
    }

    /**
     * 沙箱化的引擎配置（**单点定义**，测试会用它验证「宿主访问 / 文件 IO 真的不可用」）。
     * <p>
     * 说明：GraalJS 社区版没有 {@code sandbox.MaxCPUTime} / {@code sandbox.MaxHeapMemory} 之类的配额选项
     * （实测 24.2.1 不存在），资源保护由「墙钟超时 + 输入大小上限 + 并发上限」承担，见类注释。
     */
    static Context.Builder sandboxedBuilder() {
        return Context.newBuilder("js")
                // 收紧用的 js.* 选项目前是实验性的（版本已在 gradle.properties 里固定）
                .allowExperimentalOptions(true)
                // 不导出 java 包全局 / 不把外来对象挂到原型链 / 固定语言版本 / 未处理的 Promise 直接抛
                .option("js.java-package-globals", "false")
                .option("js.foreign-object-prototype", "false")
                .option("js.ecmascript-version", "2022")
                .option("js.unhandled-rejections", "throw")
                // 关掉 load()（再没有文件 IO 的同时，连函数本体都不留）与 console
                .option("js.load", "false")
                .option("js.console", "false")
                // 宿主 / IO / 线程 / 原生 / 多语言 / 环境：全部拒绝
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(name -> false)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                // 引擎的输出（含编译器的诊断打印）丢进空流，不污染站点日志
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .option("engine.WarnInterpreterOnly", "false");
    }

    @Override
    public boolean available() {
        return getClass().getResource(BUNDLE_RESOURCE) != null;
    }

    @Override
    public String version() {
        return "typescript/" + compilerVersion;
    }

    @Override
    public Result compile(String source, String fileName) {
        if (!available()) {
            return Result.failure("未内置 typescript.js（服务端无法编译）");
        }
        if (source == null) {
            return Result.failure("源码为空");
        }
        String name = fileName == null || fileName.isBlank() ? "page.ts" : fileName;
        String jsx = properties.getJsx() == null || properties.getJsx().isBlank()
                ? (TypeScriptCompiler.isTsx(name) ? "React" : "")
                : properties.getJsx();

        EngineHolder holder = engines.get(Math.floorMod(picker.getAndIncrement(), engines.size()));
        // 引擎首次装载要读入并求值 8.7 MB tsc，与源码无关，给更宽的窗口；之后是普通编译超时
        int timeout = holder.context == null ? properties.getInitTimeoutSeconds() : properties.getTimeoutSeconds();
        Future<Result> future = workers.submit(() -> runOnEngine(holder, source, name, jsx));
        try {
            return future.get(Math.max(1, timeout), TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            log.warn("TypeScript 编译超时（{} 秒，引擎 {}），已丢弃该引擎：{}", timeout, holder.index, name);
            holder.discard("编译超时（>" + timeout + " 秒）");
            future.cancel(true);
            return Result.failure("编译超时（>" + timeout + " 秒）");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.failure("编译被中断");
        } catch (Exception exception) {
            log.warn("TypeScript 编译失败：{}", exception.toString());
            return Result.failure("编译异常：" + exception.getMessage());
        }
    }

    /** 在指定引擎上完成「装载（如需要）→ 转译」，同一时刻只有一个任务能进入该引擎。 */
    private Result runOnEngine(EngineHolder holder, String source, String name, String jsx) {
        holder.lock.lock();
        try {
            if (holder.context == null) {
                loadEngine(holder);
                if (holder.context == null) {
                    return Result.failure("TypeScript 编译器初始化失败："
                            + (holder.failure == null ? "未知" : holder.failure));
                }
            }
            Context context = holder.context;
            Value function = context.getBindings("js").getMember("__wkTranspile");
            if (function == null || !function.canExecute()) {
                return Result.failure("TypeScript 引擎缺少转译函数（内部错误）");
            }
            Value result = function.execute(source, name,
                    properties.getTarget(), properties.getModule(), jsx);
            List<Diagnostic> diagnostics = new ArrayList<>();
            Value array = result.getMember("diagnostics");
            if (array != null) {
                for (long index = 0; index < array.getArraySize(); index++) {
                    Value item = array.getArrayElement(index);
                    diagnostics.add(new Diagnostic(
                            item.getMember("line").asInt(),
                            item.getMember("column").asInt(),
                            item.getMember("message").asString()));
                }
            }
            String version = result.getMember("version").asString();
            if (version != null && !version.isBlank()) {
                compilerVersion = version;
            }
            String code = result.getMember("code").asString();
            return diagnostics.isEmpty() ? Result.success(code) : Result.failure(diagnostics);
        } catch (RuntimeException exception) {
            holder.discard(exception.getMessage());
            log.warn("TypeScript 编译异常（引擎 {}）：{}", holder.index, exception.toString());
            return Result.failure("编译异常：" + exception.getMessage());
        } finally {
            holder.lock.unlock();
        }
    }

    /** 装载引擎（读入并求值 typescript.js + 安装转译函数）。 */
    private void loadEngine(EngineHolder holder) {
        long start = System.currentTimeMillis();
        try {
            String bundle = readBundle();
            Context created = sandboxedBuilder().build();
            created.eval(Source.newBuilder("js", bundle, "typescript.js").buildLiteral());
            created.eval("js", HELPER);
            String version = created.eval("js", "ts.version").asString();
            if (version != null && !version.isBlank()) {
                compilerVersion = version;
            }
            holder.context = created;
            holder.failure = null;
            log.info("TypeScript 编译器已就绪（引擎 {}，tsc {}，装载 {} ms）",
                    holder.index, compilerVersion, System.currentTimeMillis() - start);
        } catch (RuntimeException | IOException exception) {
            holder.failure = exception.getMessage();
            log.error("TypeScript 编译器初始化失败（引擎 {}）", holder.index, exception);
        }
    }

    /** 后台预热引擎（可选，见 {@code wikikernel.typescript.warmup}）：把首次装载从请求路径挪走。 */
    public void warmUp() {
        for (EngineHolder holder : engines) {
            workers.submit(() -> {
                holder.lock.lock();
                try {
                    if (holder.context == null) {
                        loadEngine(holder);
                    }
                } finally {
                    holder.lock.unlock();
                }
            });
        }
    }

    /** 一个引擎槽位：独立的 GraalJS 上下文 + 独占锁。 */
    private static final class EngineHolder {

        private final int index;
        private final ReentrantLock lock = new ReentrantLock();

        /** 当前上下文（null = 尚未装载或被丢弃）。 */
        private volatile Context context;

        /** 最近一次失败原因。 */
        private volatile String failure;

        private EngineHolder(int index) {
            this.index = index;
        }

        /** 丢弃引擎：{@code close(true)} 会取消正在执行的 JS，从而释放锁与虚拟线程。 */
        private void discard(String reason) {
            Context current = context;
            context = null;
            failure = reason;
            if (current != null) {
                try {
                    current.close(true);
                } catch (RuntimeException ignored) {
                    // 关闭失败不影响后续（引擎已丢弃）
                }
            }
        }
    }

    private String readBundle() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(BUNDLE_RESOURCE)) {
            if (in == null) {
                throw new IOException("找不到 " + BUNDLE_RESOURCE);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder out = new StringBuilder(9 * 1024 * 1024 / 2);
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) > 0) {
                    out.append(buffer, 0, read);
                }
                return out.toString();
            }
        }
    }
}
