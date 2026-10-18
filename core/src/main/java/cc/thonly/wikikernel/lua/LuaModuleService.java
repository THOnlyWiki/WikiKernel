package cc.thonly.wikikernel.lua;

import cc.thonly.wikikernel.actionapi.ActionApiNamespaces;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.service.TitleBlacklistService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.WikiTitles;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;
import org.jsoup.parser.Parser;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Lua 模块（仿 MediaWiki Scribunto 的子集）：
 * <ul>
 *     <li>{@code {{#invoke:模块|函数|…}}} 运行「模块:」命名空间页面的 Lua 代码；</li>
 *     <li>沙盒：没有 {@code os} / {@code io} / {@code package} / {@code dofile} /
 *     {@code loadfile} / {@code load&#42;} / {@code luajava} / {@code print}；
 *     {@code require} 只允许「模块:」命名空间与内置 {@code libraryUtil}；Lua 5.1 兼容层
 *     （{@code unpack} / {@code table.getn} / {@code table.maxn}，见 {@link Lua51Shim}）；</li>
 *     <li>指令数上限：死循环以「运行超时」错误终止（pcall 也拦不住），不会卡住请求；</li>
 *     <li>按模块修订号缓存编译结果，模块一改立即重新编译；<b>模块依赖追踪</b>：模块用
 *     {@code mw.loadData} 加载的数据模块会记入依赖，依赖修订号变化时自动重新加载（含链式依赖）；</li>
 *     <li>{@code mw} 库：{@code frame.args / getParent / getTitle}、{@code mw.text}、{@code mw.title}、
 *     {@code mw.uri}、{@code mw.log}、{@code mw.getCurrentFrame}、{@code mw.html}（{@link LuaHtmlLibrary}）、
 *     {@code mw.loadData}、{@code mw.site}、{@code mw.message}。</li>
 * </ul>
 */
@Service
public class LuaModuleService {

    /** 单次调用的指令数上限：约百毫秒量级，足以运行常用模块，又能拦住死循环。 */
    public static final int MAX_INSTRUCTIONS = 1_000_000;

    /** 编译缓存上限（按模块标题）。 */
    private static final int MAX_CACHE = 64;

    /** 数据缓存上限（按数据模块标题）。 */
    private static final int MAX_DATA_CACHE = 128;

    /** 模块命名空间前缀（与 {@code WikiNamespaces} 一致；带冒号的别名都支持）。 */
    private static final List<String> MODULE_PREFIXES = List.of("模块:", "Module:");

    private final WikiPageService wikiPageService;
    private final MessageSource messageSource;
    private final LuaSiteInfoProvider siteInfoProvider;
    private final TitleBlacklistService titleBlacklistService;

    /** 由渲染器提供的 frame 扩展能力（preprocess / 模板 / 解析器函数）；非渲染调用时为 null。 */
    public interface FrameExpander {
        String preprocess(String text);

        String expandTemplate(String name, Map<String, String> args);

        String callParserFunction(String name, Map<String, String> args);
    }

    private static final ThreadLocal<FrameExpander> FRAME_EXPANDER = new ThreadLocal<>();

    public static void setFrameExpander(FrameExpander expander) {
        FRAME_EXPANDER.set(expander);
    }

    public static void clearFrameExpander() {
        FRAME_EXPANDER.remove();
    }

    /** 设置新 expander 并返回旧的（嵌套 #invoke 时恢复）。 */
    public static FrameExpander swapFrameExpander(FrameExpander expander) {
        FrameExpander previous = FRAME_EXPANDER.get();
        if (expander == null) {
            FRAME_EXPANDER.remove();
        } else {
            FRAME_EXPANDER.set(expander);
        }
        return previous;
    }

    static FrameExpander currentFrameExpander() {
        return FRAME_EXPANDER.get();
    }

    /** 模块标题 → 编译结果（LRU，访问加锁）。 */
    private final Map<String, Compiled> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Compiled> eldest) {
            return size() > MAX_CACHE;
        }
    };

    /** 数据模块标题 → 已加载的数据表 + 依赖快照（见 {@code mw.loadData}）。 */
    private final Map<String, DataEntry> dataCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DataEntry> eldest) {
            return size() > MAX_DATA_CACHE;
        }
    };

    /** 编译结果：额外记录 html 库与 {@code mw.loadData} 依赖（标题 → 加载时的修订号）。 */
    private record Compiled(long revisionId, Globals globals, LuaValue chunk, BoundedDebugLib debug,
                            LuaValue htmlLib, Map<String, Long> dependencies) {
    }

    /** 数据缓存项：修订号 + 依赖快照；依赖任一变化即整体重新加载。 */
    private record DataEntry(long revisionId, Map<String, Long> dependencies, LuaValue table) {
    }

    /**
     * 一次 {@code #invoke} 的 {@code require} 上下文：
     * 模块名 → 已加载表（同一页面内复用）、正在加载的模块（循环检测）、依赖修订号。
     * <p>
     * Scribunto 模块大量使用 {@code require('Module:…')} / {@code require('libraryUtil')}；
     * 这里在沙盒里补一个受控 require，只允许「模块:」命名空间和内置 libraryUtil。
     */
    private final class RequireContext {

        private final LuaTable frame;
        private final String pageTitle;
        private final Map<String, Long> dependencies;
        private final Globals rootGlobals;
        private final Map<String, LuaValue> loaded = new LinkedHashMap<>();
        private final Set<String> loading = new LinkedHashSet<>();
        private final LuaValue function;

        private RequireContext(LuaTable frame, String pageTitle, Map<String, Long> dependencies,
                               Globals rootGlobals) {
            this.frame = frame;
            this.pageTitle = pageTitle;
            this.dependencies = dependencies;
            this.rootGlobals = rootGlobals;
            this.function = new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue name) {
                    return requireModule(name.checkjstring(), RequireContext.this);
                }
            };
        }

        private LuaValue function() {
            return function;
        }
    }

    public LuaModuleService(WikiPageService wikiPageService, MessageSource messageSource,
                            LuaSiteInfoProvider siteInfoProvider,
                            TitleBlacklistService titleBlacklistService) {
        this.wikiPageService = wikiPageService;
        this.messageSource = messageSource;
        this.siteInfoProvider = siteInfoProvider;
        this.titleBlacklistService = titleBlacklistService;
    }

    /** 调用结果：成功返回可再解析的文本；失败返回本地化的纯文本错误（由渲染器转义包装）。 */
    public record Result(boolean ok, String text) {
        public static Result ok(String text) {
            return new Result(true, text == null ? "" : text);
        }

        public static Result error(String message) {
            return new Result(false, message);
        }
    }

    /**
     * 运行 {@code {{#invoke:模块|函数|…}}}。
     *
     * @param positional 位置参数（键为 "1"、"2"…；不改空白，仿 Scribunto）
     * @param named      命名参数（已去首尾空白）
     * @param parentArgs 调用方模板的参数（{@code frame:getParent().args}；直接调用为 {@code null}）
     */
    public Result invoke(String moduleName, String functionName,
                         Map<String, String> positional, Map<String, String> named,
                         Map<String, String> parentArgs, String parentTitle, String pageTitle) {
        Optional<ModuleSource> module = findModule(moduleName);
        if (module.isEmpty()) {
            return Result.error(message("lua.error.moduleMissing", moduleName));
        }
        Compiled compiled;
        try {
            compiled = compiled(module.get());
        } catch (RuntimeException exception) {
            return Result.error(message("lua.error.script", firstLine(exception)));
        }
        synchronized (compiled.globals()) {
            compiled.debug().reset();
            Map<String, Long> dependencies = new LinkedHashMap<>();
            try {
                LuaTable frame = ScribuntoLibrary.frame(
                        positional, named, parentArgs, parentTitle, pageTitle);
                RequireContext requireContext = new RequireContext(
                        frame, pageTitle, dependencies, compiled.globals());
                installEnvironment(compiled, requireContext);
                LuaValue moduleTable = compiled.chunk().call();
                if (!moduleTable.istable()) {
                    return Result.error(message("lua.error.moduleTable", module.get().title()));
                }
                LuaValue function = moduleTable.get(functionName);
                if (function.isnil()) {
                    return Result.error(message("lua.error.functionMissing",
                            module.get().title(), functionName));
                }
                if (!function.isfunction()) {
                    return Result.error(message("lua.error.notFunction", functionName));
                }
                LuaValue result = function.call(frame);
                return convert(result);
            } catch (InstructionLimitException exception) {
                return Result.error(message("lua.error.timeout", module.get().title()));
            } catch (LuaError exception) {
                if (exception.getCause() instanceof InstructionLimitException) {
                    return Result.error(message("lua.error.timeout", module.get().title()));
                }
                return Result.error(message("lua.error.script", firstLine(exception)));
            } catch (RuntimeException exception) {
                if (exception.getCause() instanceof InstructionLimitException) {
                    return Result.error(message("lua.error.timeout", module.get().title()));
                }
                return Result.error(message("lua.error.script", firstLine(exception)));
            } finally {
                // 依赖追踪：把本次用到的数据模块（含链式）记进编译结果
                compiled.dependencies().putAll(dependencies);
            }
        }
    }

    private Result convert(LuaValue value) {
        if (value.isnil()) {
            return Result.ok("");
        }
        if (value.isstring() || value.isnumber()) {
            return Result.ok(value.tojstring());
        }
        return Result.error(message("lua.error.returnType", value.typename()));
    }

    // ==================== 模块解析与编译 ====================

    private record ModuleSource(String title, long revisionId, String source) {
    }

    private Optional<ModuleSource> findModule(String name) {
        String raw = name == null ? "" : name.trim();
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        List<String> candidates = new ArrayList<>();
        if (raw.indexOf(':') >= 0) {
            candidates.add(raw);
            if (raw.regionMatches(true, 0, "Module:", 0, 7)) {
                candidates.add("模块:" + raw.substring(7));
            } else if (raw.startsWith("模块:")) {
                candidates.add("Module:" + raw.substring(3));
            }
            if (raw.indexOf('_') >= 0) {
                candidates.add(raw.replace('_', ' '));
            }
        } else {
            for (String prefix : MODULE_PREFIXES) {
                candidates.add(prefix + raw);
            }
            if (raw.indexOf('_') >= 0) {
                for (String prefix : MODULE_PREFIXES) {
                    candidates.add(prefix + raw.replace('_', ' '));
                }
            }
        }
        for (String title : candidates.stream().distinct().toList()) {
            Optional<WikiPage> page = wikiPageService.getVisibleByTitle(title);
            if (page.isPresent()) {
                String source = wikiPageService.getLatestContent(page.get().getId()).orElse("");
                long revision = page.get().getLatestRevisionId() == null
                        ? 0 : page.get().getLatestRevisionId();
                return Optional.of(new ModuleSource(page.get().getTitle(), revision, source));
            }
        }
        return Optional.empty();
    }

    /** 模块标题的当前修订号（不存在 = -1；依赖检查用，只查页面不取正文）。 */
    private long latestRevisionOf(String title) {
        return wikiPageService.getVisibleByTitle(title)
                .map(page -> page.getLatestRevisionId() == null ? 0L : page.getLatestRevisionId())
                .orElse(-1L);
    }

    /** 依赖是否全部仍是加载时的修订号（任一被编辑过 → false，需要重新加载）。 */
    private boolean dependenciesCurrent(Map<String, Long> dependencies) {
        for (Map.Entry<String, Long> entry : dependencies.entrySet()) {
            if (latestRevisionOf(entry.getKey()) != entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private Compiled compiled(ModuleSource module) {
        Compiled cached;
        synchronized (cache) {
            cached = cache.get(module.title());
        }
        if (cached != null && cached.revisionId() == module.revisionId()
                && dependenciesCurrent(cached.dependencies())) {
            return cached;
        }
        BoundedDebugLib debug = new BoundedDebugLib(MAX_INSTRUCTIONS);
        Globals globals = sandbox(debug);
        LuaValue chunk = globals.load(module.source(), "@" + module.title());
        Compiled compiled = new Compiled(module.revisionId(), globals, chunk, debug,
                LuaHtmlLibrary.load(globals), new ConcurrentHashMap<>());
        synchronized (cache) {
            cache.put(module.title(), compiled);
        }
        return compiled;
    }

    // ==================== require（模块依赖 + libraryUtil） ====================

    /** 给一个编译后的模块设置本次调用的 {@code mw} 与受控 {@code require}。 */
    private void installEnvironment(Compiled compiled, RequireContext context) {
        Function<String, LuaValue> loadDataCall = name -> loadData(name, context.dependencies);
        compiled.globals().set("mw", ScribuntoLibrary.mw(compiled.globals(), context.frame,
                context.pageTitle, compiled.htmlLib(), siteInfoProvider, messageSource, loadDataCall,
                this::pageContent, titleBlacklistService));
        compiled.globals().set("require", context.function());
    }

    /**
     * 受控 require：只允许「模块:」命名空间与 Scribunto 内置 {@code libraryUtil}；
     * 同一页面内复用已加载表，循环 require 报错，并把依赖修订号记入当前调用。
     */
    private LuaValue requireModule(String name, RequireContext context) {
        String raw = name == null ? "" : name.trim();
        if (raw.isEmpty()) {
            throw new LuaError(message("lua.error.moduleMissing", name));
        }
        if ("libraryUtil".equalsIgnoreCase(raw)) {
            LuaValue cached = context.loaded.get("libraryUtil");
            if (cached == null) {
                cached = LuaLibraryUtil.load(context.rootGlobals);
                context.loaded.put("libraryUtil", cached);
            }
            return cached;
        }
        Optional<ModuleSource> found = findModule(raw);
        if (found.isEmpty() && raw.indexOf('_') >= 0) {
            // MediaWiki 标题里下划线等价于空格（Citation/CS1/Date_validation 等）
            found = findModule(raw.replace('_', ' '));
        }
        if (found.isEmpty() && raw.matches("(?i).*Date[ _]validation/sandbox$")) {
            found = findModule(raw.replaceAll("(?i)/sandbox$", "").replace('_', ' '));
        }
        if (found.isEmpty()) {
            LuaValue fallback = fallbackModule(raw);
            if (fallback != null) {
                return fallback;
            }
            throw new LuaError(message("lua.error.moduleMissing", raw));
        }
        ModuleSource module = found.get();
        String title = module.title();
        context.dependencies.put(title, module.revisionId());
        LuaValue cached = context.loaded.get(title);
        if (cached != null) {
            return cached;
        }
        if (!context.loading.add(title)) {
            throw new LuaError(message("lua.error.loop", title));
        }
        try {
            Compiled compiled = compiled(module);
            synchronized (compiled.globals()) {
                compiled.debug().reset();
                installEnvironment(compiled, context);
                LuaValue value = compiled.chunk().call();
                if (value.isnil()) {
                    value = LuaValue.TRUE;
                }
                context.loaded.put(title, value);
                compiled.dependencies().putAll(context.dependencies);
                return value;
            }
        } finally {
            context.loading.remove(title);
        }
    }

    /**
     * 少数扩展依赖模块在本地缺失时的最小兼容实现（不写入页面内容）：
     * Fullurl、Submit an edit request、Vandal-m、Color contrast。
     * 只在 require 找不到页面时使用，不会覆盖真实模块。
     */
    private LuaValue fallbackModule(String name) {
        String key = name == null ? "" : name.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
        int colon = key.indexOf(':');
        String bare = colon >= 0 ? key.substring(colon + 1).trim() : key;
        if (bare.startsWith("citation/cs1/date validation")) {
            LuaTable table = new LuaTable();
            table.set("dates", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return LuaValue.NIL;
                }
            });
            table.set("year_date_check", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return LuaValue.NIL;
                }
            });
            return table;
        }
        if (bare.equals("fullurl")) {            LuaTable table = new LuaTable();
            table.set("_fullurl2", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    String page = values.arg(1).optjstring("");
                    String url = siteInfoProvider == null ? WikiUrls.pagePath(page)
                            : siteInfoProvider.server() + WikiUrls.pagePath(page);
                    String query = queryString(values.arg(2));
                    if (!query.isEmpty()) {
                        url += (url.contains("?") ? "&" : "?") + query;
                    }
                    String display = values.arg(3).optjstring("");
                    return LuaValue.valueOf(display.isBlank() ? url : "[" + url + " " + display + "]");
                }
            });
            return table;
        }
        if (bare.equals("submit an edit request")) {
            LuaTable table = new LuaTable();
            VarArgFunction link = new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    LuaTable args = values.arg(1).istable() ? values.arg(1).checktable() : new LuaTable();
                    String type = args.get("type").optjstring("");
                    String display = args.get("display").optjstring("提交编辑请求");
                    String url = WikiUrls.pagePath("特殊:编辑请求") + "?type="
                            + URLEncoder.encode(type, StandardCharsets.UTF_8);
                    return LuaValue.valueOf("[" + url + " " + display + "]");
                }
            };
            table.set("_link", link);
            table.set("_main", link);
            return table;
        }
        if (bare.equals("vandal-m")) {
            LuaTable table = new LuaTable();
            table.set("_main", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    LuaTable args = values.arg(1).istable() ? values.arg(1).checktable() : new LuaTable();
                    String user = args.get(1).optjstring("");
                    if (user.isBlank()) {
                        return LuaValue.valueOf("");
                    }
                    return LuaValue.valueOf("[[" + WikiUrls.pagePath("特殊:用户贡献/" + user)
                            + "|" + user + "]]");
                }
            });
            return table;
        }
        if (bare.equals("color contrast")) {
            LuaTable table = new LuaTable();
            table.set("_contrast_ratio", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return LuaValue.valueOf(0);
                }
            });
            table.set("_greatercontrast", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return values.arg(1);
                }
            });
            return table;
        }
        return null;
    }

    private static String queryString(LuaValue value) {
        if (value == null || !value.istable()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        LuaTable table = value.checktable();
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            key = next.arg1();
            if (key.isnil()) {
                break;
            }
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(URLEncoder.encode(key.tojstring(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(next.arg(2).tojstring(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    // ==================== mw.loadData（数据模块 + 依赖追踪） ====================

    /**
     * {@code mw.loadData(模块名)}：加载只含数据的模块（返回值不能有函数），按修订号缓存；
     * 依赖（数据模块自己再 loadData 的模块）任一被编辑时自动重新加载。
     */
    private LuaValue loadData(String name, Map<String, Long> recorder) {
        Optional<ModuleSource> found = findModule(name);
        if (found.isEmpty()) {
            throw new LuaError(message("lua.error.dataMissing", name));
        }
        ModuleSource module = found.get();
        recorder.put(module.title(), module.revisionId());
        synchronized (dataCache) {
            DataEntry entry = dataCache.get(module.title());
            if (entry != null && entry.revisionId() == module.revisionId()
                    && dependenciesCurrent(entry.dependencies())) {
                return entry.table();
            }
        }

        Compiled compiled = compiled(module);
        Map<String, Long> nested = new LinkedHashMap<>();
        LuaValue table;
        synchronized (compiled.globals()) {
            compiled.debug().reset();
            RequireContext requireContext = new RequireContext(
                    null, module.title(), nested, compiled.globals());
            installEnvironment(compiled, requireContext);
            table = compiled.chunk().call();
            validateData(table);
            compiled.dependencies().putAll(nested);
            recorder.putAll(nested);
        }
        synchronized (dataCache) {
            dataCache.put(module.title(), new DataEntry(module.revisionId(), Map.copyOf(nested), table));
        }
        return table;
    }

    /** 数据模块校验：只允许字符串 / 数字 / 布尔 / 子表（键为字符串或数字），不允许循环引用。 */
    private void validateData(LuaValue value) {
        if (!value.istable()) {
            throw new LuaError(message("lua.error.dataInvalid", value.typename()));
        }
        validateDataTable(value.checktable(), new LinkedHashSet<>());
    }

    private void validateDataTable(LuaTable table, Set<LuaTable> path) {
        if (!path.add(table)) {
            throw new LuaError(message("lua.error.dataCycle"));
        }
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs next = table.next(key);
            LuaValue current = next.arg1();
            if (current.isnil()) {
                break;
            }
            key = current;
            if (!current.isstring() && !current.isnumber()) {
                throw new LuaError(message("lua.error.dataInvalid", current.typename()));
            }
            LuaValue item = next.arg(2);
            if (item.isnil() || item.isboolean() || item.isnumber() || item.isstring()) {
                continue;
            }
            if (!item.istable()) {
                throw new LuaError(message("lua.error.dataInvalid", item.typename()));
            }
            validateDataTable(item.checktable(), path);
        }
        path.remove(table);
    }

    /** 沙盒 Globals：只加载安全基础库，去掉系统与动态加载入口；补 Lua 5.1 兼容入口。 */
    private static Globals sandbox(BoundedDebugLib debug) {
        Globals globals = new Globals();
        globals.load(new JseBaseLib());
        // LuaJ 的库安装会访问 package.loaded：先加载 PackageLib，装完立即移除（不暴露 require/package）
        globals.load(new org.luaj.vm2.lib.PackageLib());
        globals.load(new TableLib());
        globals.load(new StringLib());
        // LuaJ 自带的 string.format 会忽略 %f/%s 精度与浮点宽度：替换为修正版
        LuaFormat.install(globals.get("string"));
        globals.load(new JseMathLib());
        globals.load(debug);
        // 安装字符串编译器 / 字节码加载器（JsePlatform 的标准步骤；沙盒里只用于编译模块源码）
        org.luaj.vm2.LoadState.install(globals);
        org.luaj.vm2.compiler.LuaC.install(globals);
        for (String blocked : List.of("debug", "dofile", "loadfile", "load", "loadstring",
                "require", "package", "print", "module", "collectgarbage")) {
            globals.set(blocked, LuaValue.NIL);
        }
        // Lua 5.1 兼容层（unpack / table.getn / table.maxn）
        Lua51Shim.install(globals);
        return globals;
    }

    /** 指令数超限：普通异常（不是 LuaError），确保脚本里的 pcall 无法吞掉。 */
    static final class InstructionLimitException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static final class BoundedDebugLib extends DebugLib {

        private final long limit;
        private long remaining;

        BoundedDebugLib(long limit) {
            this.limit = limit;
            this.remaining = limit;
        }

        void reset() {
            this.remaining = limit;
        }

        @Override
        public void onInstruction(int pc, Varargs varargs, int top) {
            if (--remaining < 0) {
                throw new InstructionLimitException();
            }
            super.onInstruction(pc, varargs, top);
        }
    }

    // ==================== 错误信息 ====================

    /** 取错误第一行（去掉行号头与堆栈）。 */
    private static String firstLine(Throwable exception) {
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
        int traceback = message.indexOf("stack traceback");
        if (traceback >= 0) {
            message = message.substring(0, traceback);
        }
        message = message.strip();
        int newline = message.indexOf('\n');
        if (newline >= 0) {
            message = message.substring(0, newline).strip();
        }
        if (message.startsWith("@")) {
            int colon = message.indexOf(": ");
            if (colon > 0) {
                message = message.substring(colon + 2).trim();
            }
        }
        return message;
    }

    private String message(String key, Object... args) {
        String fallback = switch (key) {
            case "lua.error.moduleMissing" -> "脚本错误：找不到模块「" + args[0] + "」。";
            case "lua.error.functionMissing" -> "脚本错误：模块「" + args[0] + "」没有函数「" + args[1] + "」。";
            case "lua.error.notFunction" -> "脚本错误：「" + args[0] + "」不是函数。";
            case "lua.error.moduleTable" -> "脚本错误：模块「" + args[0] + "」没有返回 table。";
            case "lua.error.loop" -> "脚本错误：Lua 模块调用出现循环「" + args[0] + "」。";
            case "lua.error.timeout" -> "脚本错误：模块「" + args[0] + "」运行超时（可能有死循环）。";
            case "lua.error.returnType" -> "脚本错误：函数返回了不允许的类型「" + args[0]
                    + "」（只能返回字符串或数字）。";
            case "lua.error.dataMissing" -> "脚本错误：loadData 找不到模块「" + args[0] + "」。";
            case "lua.error.dataInvalid" -> "脚本错误：数据模块只能包含字符串 / 数字 / 布尔 / 子表（发现「"
                    + args[0] + "」）。";
            case "lua.error.dataCycle" -> "脚本错误：数据模块不能包含循环引用。";
            case "lua.error.script" -> "脚本错误：" + args[0];
            default -> key;
        };
        return messageSource == null ? fallback
                : messageSource.getMessage(key, args, fallback, LocaleContextHolder.getLocale());
    }

    // ==================== Scribunto 兼容库（frame / mw / title / uri / html / loadData / site / message） ====================

    /** 公开给 core 解析器函数 {@code #expr} 的简单算术求值（失败时原样返回）。 */
    public static String evaluateExpression(String expression) {
        return ScribuntoLibrary.evaluateExpression(expression);
    }

    /** 解析器函数 {@code #language} / 模块共用的语言名称。 */
    public static String languageDisplayName(String code) {
        return ScribuntoLibrary.languageName(code);
    }

    /** {@code mw.title:getContent()} 的页面正文来源（不存在 / 空页面返回 null）。 */
    private String pageContent(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        return wikiPageService.getVisibleByTitle(title)
                .flatMap(page -> wikiPageService.getLatestContent(page.getId()))
                .orElse(null);
    }

    static final class ScribuntoLibrary {

        private ScribuntoLibrary() {
        }

        /** frame：{@code args}（位置 + 命名）+ {@code getParent}（模板参数）+ {@code getTitle}。 */
        static LuaTable frame(Map<String, String> positional, Map<String, String> named,
                              Map<String, String> parentArgs, String parentTitle, String pageTitle) {
            LuaTable args = new LuaTable();
            positional.forEach((key, value) -> setArg(args, key, value));
            named.forEach((key, value) -> args.set(key, LuaValue.valueOf(value)));

            LuaTable frame = new LuaTable();
            frame.set("__frame", LuaValue.TRUE);
            frame.set("args", args);
            frame.set("getParent", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    // 即使直接 #invoke（没有调用方模板）也返回一个父 frame；
                    // MediaWiki 的模块（Navbox / NoteTA）会直接访问 getParent().args / getTitle()。
                    LuaTable parent = new LuaTable();
                    parent.set("args", argsTable(parentArgs == null ? Map.of() : parentArgs));
                    parent.set("getParent", new ZeroArgFunction() {
                        @Override
                        public LuaValue call() {
                            return LuaValue.NIL;
                        }
                    });
                    parent.set("getTitle", new ZeroArgFunction() {
                        @Override
                        public LuaValue call() {
                            // Scribunto 的 frame:getTitle() 返回字符串标题；父 frame = 调用 #invoke 的模板
                            return LuaValue.valueOf(parentTitle == null || parentTitle.isBlank()
                                    ? (pageTitle == null ? "" : pageTitle) : parentTitle);
                        }
                    });
                    return parent;
                }
            });
            frame.set("getTitle", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaValue.valueOf(pageTitle == null ? "" : pageTitle);
                }
            });
            frame.set("extensionTag", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return LuaValue.valueOf(extensionTag(values, selfOffset(values)));
                }
            });

            FrameExpander expander = currentFrameExpander();
            frame.set("preprocess", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    int offset = selfOffset(values);
                    String text = values.arg(1 + offset).optjstring("");
                    return LuaValue.valueOf(expander == null ? text : expander.preprocess(text));
                }
            });
            frame.set("expandTemplate", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    if (expander == null) {
                        return LuaValue.valueOf("");
                    }
                    int offset = selfOffset(values);
                    LuaTable spec = values.arg(1 + offset).istable() ? values.arg(1 + offset).checktable() : null;
                    String name = spec == null
                            ? values.arg(1 + offset).optjstring("")
                            : spec.get("title").optjstring(spec.get("name").optjstring(""));
                    LuaTable argTable = spec == null
                            ? (values.arg(2 + offset).istable() ? values.arg(2 + offset).checktable() : null)
                            : (spec.get("args").istable() ? spec.get("args").checktable() : null);
                    return LuaValue.valueOf(expander.expandTemplate(name, mapOf(argTable)));
                }
            });
            frame.set("callParserFunction", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    if (expander == null) {
                        return LuaValue.valueOf("");
                    }
                    int offset = selfOffset(values);
                    LuaTable spec = values.arg(1 + offset).istable() ? values.arg(1 + offset).checktable() : null;
                    String name = spec == null
                            ? values.arg(1 + offset).optjstring("")
                            : spec.get("name").optjstring("");
                    LuaTable argTable = spec == null
                            ? (values.arg(2 + offset).istable() ? values.arg(2 + offset).checktable() : null)
                            : (spec.get("args").istable() ? spec.get("args").checktable() : null);
                    return LuaValue.valueOf(expander.callParserFunction(name, mapOf(argTable)));
                }
            });
            frame.set("newChild", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    int offset = selfOffset(values);
                    LuaValue value = values.arg(1 + offset);
                    LuaTable spec = value.istable() ? value.checktable() : new LuaTable();
                    LuaTable childArgs = spec.get("args").istable()
                            ? spec.get("args").checktable() : new LuaTable();
                    return childFrame(childArgs, frame);
                }
            });
            frame.set("getArgument", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    int offset = selfOffset(values);
                    LuaValue value = values.arg(1 + offset);
                    return value.isnumber() ? args.get(value.toint()) : args.get(value.tojstring());
                }
            });
            return frame;
        }

        /** 子 frame（{@code frame:newChild}）：沿用父 frame 的能力，只替换 args。 */
        private static LuaTable childFrame(LuaTable childArgs, LuaTable parent) {
            LuaTable child = new LuaTable();
            child.set("__frame", LuaValue.TRUE);
            child.set("args", childArgs);
            child.set("getParent", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return parent;
                }
            });
            child.set("getTitle", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return parent.get("getTitle").call();
                }
            });
            child.set("preprocess", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return parent.get("preprocess").invoke(values);
                }
            });
            child.set("expandTemplate", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return parent.get("expandTemplate").invoke(values);
                }
            });
            child.set("callParserFunction", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return parent.get("callParserFunction").invoke(values);
                }
            });
            child.set("extensionTag", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return parent.get("extensionTag").invoke(values);
                }
            });
            child.set("newChild", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return parent.get("newChild").invoke(values);
                }
            });
            child.set("getArgument", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return parent.get("getArgument").invoke(values);
                }
            });
            return child;
        }

        /** 冒号调用 {@code frame:method(...)} 时跳过第一个 frame 参数。 */
        private static int selfOffset(Varargs values) {
            return values.arg(1).istable() && values.arg(1).get("__frame").toboolean() ? 1 : 0;
        }

        /** Lua 表 → Java 有序 Map（解析器函数 / 模板参数用）。 */
        private static Map<String, String> mapOf(LuaTable table) {
            Map<String, String> result = new LinkedHashMap<>();
            if (table == null) {
                return result;
            }
            LuaValue key = LuaValue.NIL;
            while (true) {
                Varargs next = table.next(key);
                key = next.arg1();
                if (key.isnil()) {
                    break;
                }
                result.put(key.tojstring(), next.arg(2).optjstring(""));
            }
            return result;
        }

        /**
         * {@code frame:extensionTag} 的最小兼容：
         * <ul>
         *   <li>{@code templatestyles}：返回空串（站点 Common.css 已包含 Navbox/Navbar 样式）；</li>
         *   <li>其它标签：按 {@code <tag 属性>内容</tag>} 返回，最终仍走 HTML 白名单。</li>
         * </ul>
         * 支持 Scribunto 的两种写法：{@code extensionTag{name=…, args=…, content=…}} 与
         * {@code extensionTag(name, content, args)}。
         */
        private static String extensionTag(Varargs values, int offset) {
            String name;
            String content = "";
            LuaTable attributes = null;
            if (values.arg(1 + offset).istable()) {
                LuaTable spec = values.arg(1 + offset).checktable();
                name = spec.get("name").optjstring("");
                content = spec.get("content").optjstring("");
                if (spec.get("args").istable()) {
                    attributes = spec.get("args").checktable();
                }
            } else {
                name = values.arg(1 + offset).optjstring("");
                content = values.arg(2 + offset).optjstring("");
                if (values.arg(3 + offset).istable()) {
                    attributes = values.arg(3 + offset).checktable();
                }
            }
            if (name.isBlank()) {
                return "";
            }
            if ("templatestyles".equalsIgnoreCase(name)) {
                String src = attributes == null ? "" : attributes.get("src").optjstring("");
                if (src.isBlank()) {
                    src = content;
                }
                return src.isBlank() ? ""
                        : "<templatestyles src=\"" + escapeAttribute(src) + "\"/>";
            }
            StringBuilder out = new StringBuilder("<").append(name);
            if (attributes != null) {
                LuaValue key = LuaValue.NIL;
                while (true) {
                    Varargs next = attributes.next(key);
                    key = next.arg1();
                    if (key.isnil()) {
                        break;
                    }
                    LuaValue value = next.arg(2);
                    if (!value.isnil()) {
                        out.append(' ').append(key.tojstring()).append("=\"")
                                .append(escapeAttribute(value.tojstring())).append('"');
                    }
                }
            }
            out.append('>');
            if (!content.isEmpty()) {
                out.append(content).append("</").append(name).append('>');
            } else {
                out.append("</").append(name).append('>');
            }
            return out.toString();
        }

        private static String escapeAttribute(String value) {
            return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;")
                    .replace("<", "&lt;").replace(">", "&gt;");
        }

        /** {@code mw} 表：每次调用重建（函数捕获本次 frame / 页面标题，互不影响）。 */
        static LuaTable mw(Globals globals, LuaTable frame, String pageTitle, LuaValue htmlLib,
                           LuaSiteInfoProvider siteInfo, MessageSource messageSource,
                           Function<String, LuaValue> loadDataCall,
                           Function<String, String> contentLookup,
                           TitleBlacklistService titleBlacklist) {
            LuaTable mw = new LuaTable();

            LuaTable text = new LuaTable();
            text.set("plain", oneArg(ScribuntoLibrary::plain));
            text.set("trim", oneArg(value -> value == null ? "" : value.trim()));
            text.set("upper", oneArg(value -> value == null ? "" : value.toUpperCase(Locale.ROOT)));
            text.set("lower", oneArg(value -> value == null ? "" : value.toLowerCase(Locale.ROOT)));
            text.set("len", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue value) {
                    return LuaValue.valueOf(value.isnil() ? 0 : value.tojstring().length());
                }
            });
            text.set("sub", new ThreeArgFunction() {
                @Override
                public LuaValue call(LuaValue value, LuaValue start, LuaValue end) {
                    String source = value.tojstring();
                    int length = source.length();
                    int from = start.toint();
                    int to = end.isnil() ? -1 : end.toint();
                    if (from < 0) {
                        from = length + from + 1;
                    }
                    if (to < 0) {
                        to = length + to + 1;
                    }
                    from = Math.max(1, from);
                    to = Math.min(length, to);
                    return LuaValue.valueOf(from > to ? "" : source.substring(from - 1, to));
                }
            });
            text.set("format", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    // 直接复用 Lua 的 string.format（与 Scribunto 的 mw.text.format 相同语义）
                    return globals.get("string").get("format").invoke(values);
                }
            });
            text.set("decode", oneArg(ScribuntoLibrary::decodeEntities));
            text.set("nowiki", oneArg(ScribuntoLibrary::nowiki));
            text.set("unstrip", oneArg(value -> value == null ? "" : value));
            text.set("unstripNoWiki", oneArg(value -> value == null ? "" : value));
            text.set("killMarkers", oneArg(value -> value == null ? "" : value));
            text.set("split", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return splitText(values.arg(1).optjstring(""), values.arg(2).optjstring(""));
                }
            });
            text.set("listToText", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return LuaValue.valueOf(listToText(values.arg(1), values.arg(2).optjstring(", ")));
                }
            });
            text.set("truncate", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    String source = values.arg(1).optjstring("");
                    int length = values.arg(2).optint(0);
                    String ellipsis = values.arg(3).isnil() ? "..." : values.arg(3).optjstring("...");
                    return LuaValue.valueOf(truncate(source, length, ellipsis));
                }
            });
            text.set("urlEncode", oneArg(value -> URLEncoder.encode(value == null ? "" : value,
                    StandardCharsets.UTF_8).replace("+", "%20")));
            mw.set("text", text);

            LuaTable ustring = LuaUstringLibrary.load(globals);
            mw.set("ustring", ustring);

            LuaTable title = new LuaTable();
            title.set("new", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    String text = values.arg(1).optjstring("");
                    String namespace = values.arg(2).optjstring("");
                    if (!namespace.isEmpty() && text.indexOf(':') < 0) {
                        text = namespace + ":" + text;
                    }
                    return titleTable(text, contentLookup);
                }
            });
            title.set("getCurrentTitle", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return titleTable(pageTitle, contentLookup);
                }
            });
            title.set("makeTitle", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    String namespace = values.arg(1).optjstring("");
                    String text = values.arg(2).optjstring("");
                    String fragment = values.arg(3).optjstring("");
                    String full = namespace.isEmpty() ? text : namespace + ":" + text;
                    if (!fragment.isEmpty()) {
                        full = full + "#" + fragment;
                    }
                    return titleTable(full, contentLookup);
                }
            });
            title.set("equals", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    LuaValue left = values.arg(1);
                    LuaValue right = values.arg(2);
                    if (!left.istable() || !right.istable()) {
                        return LuaValue.FALSE;
                    }
                    String a = left.get("fullText").optjstring("");
                    String b = right.get("fullText").optjstring("");
                    return LuaValue.valueOf(!a.isEmpty() && a.equals(b));
                }
            });
            mw.set("title", title);

            LuaTable language = new LuaTable();
            language.set("getContentLanguage", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return contentLanguage();
                }
            });
            language.set("fetchLanguageName", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return LuaValue.valueOf(languageName(values.arg(1).optjstring("")));
                }
            });
            language.set("fetchLanguageNames", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    String inLanguage = values.arg(1).optjstring("zh");
                    LuaTable names = new LuaTable();
                    for (String code : List.of("zh", "zh-hans", "zh-hant", "en", "ja", "ko", "de", "fr",
                            "ru", "es", "it", "pt", "pl", "nl", "sv", "uk", "vi", "th", "id", "ar")) {
                        names.set(code, LuaValue.valueOf(languageName(code)));
                    }
                    return names;
                }
            });
            language.set("isSupportedLanguage", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue value) {
                    return LuaValue.valueOf(!languageName(value.optjstring("")).equals(value.optjstring("")));
                }
            });
            language.set("isKnownLanguageTag", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue value) {
                    return LuaValue.valueOf(!languageName(value.optjstring("")).equals(value.optjstring("")));
                }
            });
            mw.set("language", language);
            mw.set("getContentLanguage", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return contentLanguage();
                }
            });
            mw.set("ext", extensionLibrary(titleBlacklist));

            LuaTable uri = new LuaTable();
            uri.set("encode", oneArg(value -> URLEncoder.encode(value == null ? "" : value,
                    StandardCharsets.UTF_8).replace("+", "%20")));
            uri.set("anchorEncode", oneArg(value -> URLEncoder.encode(value == null ? "" : value,
                    StandardCharsets.UTF_8).replace("+", "_")));
            uri.set("decode", oneArg(value -> decodePercent(value == null ? "" : value)));
            uri.set("validate", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue value) {
                    try {
                        new java.net.URI(value.optjstring(""));
                        return LuaValue.TRUE;
                    } catch (java.net.URISyntaxException exception) {
                        return LuaValue.FALSE;
                    }
                }
            });
            uri.set("new", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    LuaValue value = values.arg(1);
                    if (value.istable()) {
                        return uriTable(value.checktable());
                    }
                    return uriTable(value.optjstring(""));
                }
            });
            uri.set("parse", uri.get("new"));
            uri.set("fullUrl", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue value) {
                    return LuaValue.valueOf(value.optjstring(""));
                }
            });
            mw.set("uri", uri);

            mw.set("getCurrentFrame", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return frame == null ? LuaValue.NIL : frame;
                }
            });
            mw.set("log", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    // Scribunto 输出到调试控制台；MVP 不记录（避免日志噪声）
                    return LuaValue.NONE;
                }
            });

            // mw.html（构建器 Lua 实现，模块编译时已预载）
            mw.set("html", htmlLib == null ? new LuaTable() : htmlLib);

            // mw.loadData：数据模块（只读数据 + 依赖追踪）
            mw.set("loadData", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue value) {
                    String name = value.checkjstring();
                    try {
                        return loadDataCall.apply(name);
                    } catch (LuaError error) {
                        throw error;
                    } catch (RuntimeException exception) {
                        throw new LuaError(firstLine(exception));
                    }
                }
            });

            mw.set("clone", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue value) {
                    return cloneValue(value, new java.util.IdentityHashMap<>());
                }
            });
            mw.set("dumpObject", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue value) {
                    return LuaValue.valueOf(dumpValue(value,
                            new java.util.IdentityHashMap<>(), 0));
                }
            });
            mw.set("allToString", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    StringBuilder out = new StringBuilder();
                    for (int i = 1; i <= values.narg(); i++) {
                        out.append(values.arg(i).tojstring());
                    }
                    return LuaValue.valueOf(out.toString());
                }
            });
            mw.set("isSubsting", LuaValue.FALSE);

            // mw.site：站点信息 + 统计 + 命名空间
            mw.set("site", site(siteInfo));

            // mw.message：界面消息
            mw.set("message", messageLibrary(messageSource));
            return mw;
        }

        /** {@code mw.uri.new}：把 URL 拆成协议 / 主机 / 路径 / 查询 / 片段；无法解析时保留原始 fullUrl。 */
        static LuaTable uriTable(String value) {
            String source = value == null ? "" : value;
            LuaTable table = new LuaTable();
            try {
                java.net.URI uri = new java.net.URI(source);
                table.set("protocol", LuaValue.valueOf(uri.getScheme() == null ? "" : uri.getScheme()));
                table.set("host", LuaValue.valueOf(uri.getHost() == null ? "" : uri.getHost()));
                table.set("port", LuaValue.valueOf(uri.getPort()));
                table.set("path", LuaValue.valueOf(uri.getPath() == null ? "" : uri.getPath()));
                table.set("query", LuaValue.valueOf(uri.getQuery() == null ? "" : uri.getQuery()));
                table.set("queryString", LuaValue.valueOf(uri.getRawQuery() == null ? "" : uri.getRawQuery()));
                table.set("fragment", LuaValue.valueOf(uri.getFragment() == null ? "" : uri.getFragment()));
                table.set("userInfo", LuaValue.valueOf(uri.getUserInfo() == null ? "" : uri.getUserInfo()));
            } catch (java.net.URISyntaxException exception) {
                // 保留原始值即可
            }
            table.set("fullUrl", LuaValue.valueOf(source));
            table.set("toString", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaValue.valueOf(source);
                }
            });
            return table;
        }

        /** {@code mw.uri.new} 的表参形式：{@code protocol/host/path/query/fragment} 字段构建 URI。 */
        static LuaTable uriTable(LuaTable source) {
            LuaTable table = new LuaTable();
            for (String key : List.of("protocol", "host", "port", "path", "query", "queryString",
                    "fragment", "userInfo")) {
                table.set(key, source.get(key));
            }
            table.set("toString", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaValue.valueOf(buildUri(table));
                }
            });
            table.set("fullUrl", LuaValue.valueOf(buildUri(table)));
            return table;
        }

        private static String buildUri(LuaTable table) {
            String protocol = table.get("protocol").optjstring("");
            String userInfo = table.get("userInfo").optjstring("");
            String host = table.get("host").optjstring("");
            int port = table.get("port").optint(-1);
            String path = table.get("path").optjstring("");
            String query = table.get("query").istable()
                    ? queryString(table.get("query")) : table.get("query").optjstring("");
            String fragment = table.get("fragment").optjstring("");
            StringBuilder out = new StringBuilder();
            if (!protocol.isEmpty()) {
                out.append(protocol).append("://");
            }
            if (!userInfo.isEmpty()) {
                out.append(userInfo).append('@');
            }
            out.append(host);
            if (port > 0) {
                out.append(':').append(port);
            }
            if (!path.isEmpty()) {
                if (!path.startsWith("/")) {
                    out.append('/');
                }
                out.append(path);
            }
            if (!query.isEmpty()) {
                out.append('?').append(query);
            }
            if (!fragment.isEmpty()) {
                out.append('#').append(fragment);
            }
            return out.toString();
        }

        /** {@code mw.ext}：站点已安装扩展的少量兼容入口。 */
        static LuaTable extensionLibrary(TitleBlacklistService titleBlacklist) {
            LuaTable ext = new LuaTable();

            LuaTable blacklist = new LuaTable();
            blacklist.set("test", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    if (titleBlacklist == null) {
                        return LuaValue.NIL;
                    }
                    String title = values.arg(1).optjstring("");
                    return titleBlacklist.check(title, TitleBlacklistService.Operation.CREATE, null)
                            .<LuaValue>map(violation -> LuaValue.valueOf(violation.pattern()))
                            .orElse(LuaValue.NIL);
                }
            });
            ext.set("TitleBlacklist", blacklist);

            LuaTable parserFunctions = new LuaTable();
            parserFunctions.set("expr", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue value) {
                    return LuaValue.valueOf(evaluateExpression(value.optjstring("")));
                }
            });
            ext.set("ParserFunctions", parserFunctions);
            return ext;
        }

        /** 小型 {@code #expr} 兼容解析器：数字、括号、+ - * / % ^（够模块的常用表达式）。 */
        static String evaluateExpression(String expression) {
            String source = expression == null ? "" : expression;
            try {
                double result = new ExpressionParser(source).parse();
                if (Math.rint(result) == result && !Double.isInfinite(result)) {
                    return String.valueOf((long) result);
                }
                return String.valueOf(result);
            } catch (RuntimeException exception) {
                return source;
            }
        }

        /** 小型 {@code #expr} 兼容解析器：数字、括号、+ - * / % ^（够模块的常用表达式）。 */
        private static final class ExpressionParser {
            private final String text;
            private int index;

            private ExpressionParser(String text) {
                this.text = text == null ? "" : text;
            }

            private double parse() {
                double value = expression();
                skipSpace();
                if (index != text.length()) {
                    throw new IllegalArgumentException("trailing");
                }
                return value;
            }

            private double expression() {
                double value = comparison();
                while (true) {
                    skipSpace();
                    if (take('+')) {
                        value += comparison();
                    } else if (take('-')) {
                        value -= comparison();
                    } else {
                        return value;
                    }
                }
            }

            private double comparison() {
                double left = additive();
                while (true) {
                    skipSpace();
                    if (take('>')) {
                        boolean equal = take('=');
                        double right = additive();
                        left = equal ? (left >= right ? 1 : 0) : (left > right ? 1 : 0);
                    } else if (take('<')) {
                        if (take('>')) {
                            left = left != additive() ? 1 : 0;
                        } else {
                            boolean equal = take('=');
                            double right = additive();
                            left = equal ? (left <= right ? 1 : 0) : (left < right ? 1 : 0);
                        }
                    } else if (take('=')) {
                        take('=');
                        left = left == additive() ? 1 : 0;
                    } else if (take('!')) {
                        if (take('=')) {
                            left = left != additive() ? 1 : 0;
                        } else {
                            throw new IllegalArgumentException("operator");
                        }
                    } else {
                        return left;
                    }
                }
            }

            private double additive() {
                double value = term();
                while (true) {
                    skipSpace();
                    if (take('+')) {
                        value += term();
                    } else if (take('-')) {
                        value -= term();
                    } else {
                        return value;
                    }
                }
            }

            private double term() {
                double value = power();
                while (true) {
                    skipSpace();
                    if (take('*')) {
                        value *= power();
                    } else if (take('/')) {
                        value /= power();
                    } else if (take('%')) {
                        value %= power();
                    } else {
                        return value;
                    }
                }
            }

            private double power() {
                double value = unary();
                skipSpace();
                if (take('^')) {
                    value = Math.pow(value, power());
                }
                return value;
            }

            private double unary() {
                skipSpace();
                if (take('+')) {
                    return unary();
                }
                if (take('-')) {
                    return -unary();
                }
                if (take('(')) {
                    double value = expression();
                    skipSpace();
                    if (!take(')')) {
                        throw new IllegalArgumentException("paren");
                    }
                    return value;
                }
                return number();
            }

            private double number() {
                skipSpace();
                int start = index;
                while (index < text.length()
                        && (Character.isDigit(text.charAt(index)) || text.charAt(index) == '.')) {
                    index++;
                }
                if (start == index) {
                    throw new IllegalArgumentException("number");
                }
                return Double.parseDouble(text.substring(start, index));
            }

            private void skipSpace() {
                while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                    index++;
                }
            }

            private boolean take(char expected) {
                if (index < text.length() && text.charAt(index) == expected) {
                    index++;
                    return true;
                }
                return false;
            }
        }

        /** {@code mw.language} 的内容语言对象（中文站点；只实现模块常用的读取/格式化入口）。 */
        static LuaTable contentLanguage() {
            LuaTable language = new LuaTable();
            language.set("getCode", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaValue.valueOf("zh");
                }
            });
            language.set("getName", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaValue.valueOf("中文");
                }
            });
            language.set("formatNum", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    int offset = values.arg(1).istable() ? 1 : 0;
                    String value = values.arg(1 + offset).optjstring("");
                    String format = values.arg(2 + offset).optjstring("");
                    if (format.contains("R")) {
                        return LuaValue.valueOf(value);
                    }
                    try {
                        java.text.DecimalFormatSymbols symbols = java.text.DecimalFormatSymbols
                                .getInstance(Locale.CHINA);
                        java.text.DecimalFormat formatter = new java.text.DecimalFormat("#,##0.################", symbols);
                        formatter.setGroupingUsed(!format.contains("NOSEP"));
                        String result = formatter.format(Double.parseDouble(value));
                        if (format.contains("NOSEP")) {
                            result = result.replace(",", "");
                        }
                        return LuaValue.valueOf(result);
                    } catch (NumberFormatException exception) {
                        return LuaValue.valueOf(value);
                    }
                }
            });
            language.set("parseFormattedNumber", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    int offset = values.arg(1).istable() ? 1 : 0;
                    String text = values.arg(1 + offset).optjstring("").replace(",", "").trim();
                    try {
                        return LuaValue.valueOf(Double.parseDouble(text));
                    } catch (NumberFormatException exception) {
                        return LuaValue.ZERO;
                    }
                }
            });
            language.set("formatDate", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    int offset = values.arg(1).istable() ? 1 : 0;
                    return LuaValue.valueOf(values.arg(2 + offset).optjstring(values.arg(1 + offset).optjstring("")));
                }
            });
            language.set("plural", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    int offset = values.arg(1).istable() ? 1 : 0;
                    double number = values.arg(1 + offset).optdouble(0);
                    if (values.narg() <= 2 + offset) {
                        return values.arg(2 + offset);
                    }
                    int form = Math.abs(number - 1) < 1e-9 ? 1 : 2;
                    LuaValue chosen = values.arg(form + 1 + offset);
                    return chosen.isnil() ? values.arg(2 + offset) : chosen;
                }
            });
            language.set("grammar", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    int offset = values.arg(1).istable() ? 1 : 0;
                    return values.arg(2 + offset).isnil() ? values.arg(1 + offset) : values.arg(2 + offset);
                }
            });
            language.set("gender", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    int offset = values.arg(1).istable() ? 1 : 0;
                    String gender = values.arg(1 + offset).optjstring("");
                    int index = "f".equalsIgnoreCase(gender) || "female".equalsIgnoreCase(gender) ? 3 + offset
                            : "n".equalsIgnoreCase(gender) || "neutral".equalsIgnoreCase(gender) ? 4 + offset : 2 + offset;
                    LuaValue value = values.arg(index);
                    if (value.isnil()) {
                        value = values.arg(2 + offset);
                    }
                    return value.isnil() ? LuaValue.valueOf("") : value;
                }
            });
            language.set("convert", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    int offset = values.arg(1).istable() ? 1 : 0;
                    return values.arg(1 + offset);
                }
            });
            language.set("ucfirst", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    int offset = values.arg(1).istable() ? 1 : 0;
                    String text = values.arg(1 + offset).optjstring("");
                    return LuaValue.valueOf(text.isEmpty() ? text
                            : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1));
                }
            });
            language.set("lcfirst", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    int offset = values.arg(1).istable() ? 1 : 0;
                    String text = values.arg(1 + offset).optjstring("");
                    return LuaValue.valueOf(text.isEmpty() ? text
                            : text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1));
                }
            });
            language.set("getDir", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaValue.valueOf("ltr");
                }
            });
            language.set("getDirMark", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaValue.valueOf("\u200E");
                }
            });
            language.set("isRTL", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaValue.FALSE;
                }
            });
            language.set("isKnownLanguageTag", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue value) {
                    String code = value.optjstring("");
                    return LuaValue.valueOf(!languageName(code).equals(code));
                }
            });
            LuaTable fallbacks = new LuaTable();
            fallbacks.set(1, LuaValue.valueOf("en"));
            language.set("getFallbackLanguages", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return fallbacks;
                }
            });
            return language;
        }

        private static String languageName(String code) {
            return switch (code == null ? "" : code.toLowerCase(Locale.ROOT)) {
                case "zh", "zh-hans", "zh-cn", "zh-sg" -> "中文";
                case "zh-hant", "zh-tw", "zh-hk", "zh-mo" -> "中文（繁體）";
                case "en" -> "English";
                case "ja" -> "日本語";
                case "ko" -> "한국어";
                case "de" -> "Deutsch";
                case "fr" -> "français";
                case "ru" -> "русский";
                case "es" -> "español";
                case "it" -> "italiano";
                case "pt" -> "português";
                case "pl" -> "polski";
                case "nl" -> "Nederlands";
                case "sv" -> "svenska";
                case "uk" -> "українська";
                case "vi" -> "Tiếng Việt";
                case "th" -> "ไทย";
                case "id" -> "Bahasa Indonesia";
                case "ar" -> "العربية";
                default -> code == null ? "" : code;
            };
        }

        /** {@code mw.site}：站点名 / 版本 / 路径 / 统计 / 命名空间。 */
        static LuaTable site(LuaSiteInfoProvider siteInfo) {
            LuaTable site = new LuaTable();
            if (siteInfo == null) {
                return site;
            }
            site.set("siteName", LuaValue.valueOf(siteInfo.siteName()));
            site.set("currentVersion", LuaValue.valueOf(siteInfo.currentVersion()));
            site.set("server", LuaValue.valueOf(siteInfo.server()));
            site.set("scriptPath", LuaValue.valueOf(siteInfo.scriptPath()));
            site.set("articlePath", LuaValue.valueOf(siteInfo.articlePath()));

            LuaSiteInfoProvider.Stats stats = siteInfo.stats();
            LuaTable statsTable = new LuaTable();
            statsTable.set("pages", LuaValue.valueOf(stats.pages()));
            statsTable.set("articles", LuaValue.valueOf(stats.articles()));
            statsTable.set("edits", LuaValue.valueOf(stats.edits()));
            statsTable.set("files", LuaValue.valueOf(stats.files()));
            statsTable.set("images", LuaValue.valueOf(stats.files()));
            statsTable.set("users", LuaValue.valueOf(stats.users()));
            statsTable.set("admins", LuaValue.valueOf(stats.admins()));
            site.set("stats", statsTable);

            LuaTable namespaces = new LuaTable();
            LuaTable subjectNamespaces = new LuaTable();
            Locale locale = LocaleContextHolder.getLocale();
            for (ActionApiNamespaces.Ns ns : ActionApiNamespaces.all()) {
                LuaTable entry = new LuaTable();
                entry.set("id", LuaValue.valueOf(ns.id()));
                entry.set("name", LuaValue.valueOf(ActionApiNamespaces.displayName(ns.id(), locale)));
                entry.set("canonicalName", LuaValue.valueOf(ns.name()));
                entry.set("isTalk", LuaValue.valueOf(ns.id() > 0 && ns.id() % 2 == 1));
                entry.set("aliases", new LuaTable());
                namespaces.set(ns.id(), entry);
                namespaces.set(ns.name(), entry);
                namespaces.set(entry.get("name"), entry);
                if (ns.id() % 2 == 0) {
                    subjectNamespaces.set(ns.id(), entry);
                    subjectNamespaces.set(ns.name(), entry);
                    subjectNamespaces.set(entry.get("name"), entry);
                }
            }
            site.set("namespaces", namespaces);
            site.set("subjectNamespaces", subjectNamespaces);
            return site;
        }

        /** {@code mw.message}：{@code new} / {@code newRawMessage}（原始消息用 {@code $1} 占位）。 */
        static LuaTable messageLibrary(MessageSource messageSource) {
            LuaTable lib = new LuaTable();
            lib.set("new", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs args) {
                    return messageObject(args.checkjstring(1), false, messageSource, paramsFrom(args, 2));
                }
            });
            lib.set("newRawMessage", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs args) {
                    return messageObject(args.checkjstring(1), true, messageSource, paramsFrom(args, 2));
                }
            });
            return lib;
        }

        private static List<String> paramsFrom(Varargs args, int from) {
            List<String> values = new ArrayList<>();
            for (int i = from; i <= args.narg(); i++) {
                LuaValue item = args.arg(i);
                values.add(item.isnil() ? "" : item.tojstring());
            }
            return values;
        }

        private static LuaValue messageObject(String value, boolean raw, MessageSource messageSource,
                                              List<String> initialParams) {
            List<String> params = new ArrayList<>(initialParams);
            LuaTable message = new LuaTable();
            message.set("params", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs args) {
                    int from = args.arg(1).istable() ? 2 : 1;
                    params.addAll(paramsFrom(args, from));
                    return message;
                }
            });
            message.set("numParams", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaValue.valueOf(params.size());
                }
            });
            message.set("text", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaValue.valueOf(messageText(value, raw, messageSource, params));
                }
            });
            message.set("plain", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    String text = messageText(value, raw, messageSource, params);
                    return LuaValue.valueOf(isMissing(value, raw, messageSource) ? text : plain(text));
                }
            });
            message.set("exists", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaValue.valueOf(!isMissing(value, raw, messageSource));
                }
            });
            message.set("isBlank", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    String text = messageText(value, raw, messageSource, params).trim();
                    return LuaValue.valueOf(text.isEmpty() || "-".equals(text));
                }
            });
            return message;
        }

        private static boolean isMissing(String value, boolean raw, MessageSource messageSource) {
            return !raw && resolve(messageSource, value, List.of()) == null;
        }

        private static String messageText(String value, boolean raw, MessageSource messageSource,
                                          List<String> params) {
            if (raw) {
                return substitute(value, params);
            }
            String resolved = resolve(messageSource, value, params);
            return resolved == null ? "<" + value + ">" : resolved;
        }

        /** 查本地化消息（{@code params} 按 {@code {0}} 占位替换）；找不到返回 null。 */
        private static String resolve(MessageSource messageSource, String key, List<String> params) {
            if (messageSource == null) {
                return null;
            }
            Object[] args = params.isEmpty() ? null : params.toArray();
            try {
                String value = messageSource.getMessage(key, args, null, LocaleContextHolder.getLocale());
                // 项目约定：缺翻译时消息源返回**键本身**（不再回落中文）——对 Lua 来说这等于「没有这条消息」
                return value != null && value.equals(key) ? null : value;
            } catch (RuntimeException exception) {
                // 消息里含未转义的 { } 时 MessageFormat 会报错：退回不带参数的原文
                return messageSource.getMessage(key, null, null, LocaleContextHolder.getLocale());
            }
        }

        /** 原始消息的 {@code $1} / {@code $2} 参数替换（Scribunto 的 newRawMessage 语义）。 */
        private static String substitute(String text, List<String> params) {
            String result = text;
            for (int i = params.size(); i >= 1; i--) {
                result = result.replace("$" + i, params.get(i - 1));
            }
            return result;
        }

        /** 主命名空间外的标题 → 对应讨论页标题（Navbar 的「讨论」链接用）。 */
        private static String talkTitle(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String[][] pairs = {
                    {"模板:", "模板讨论:"},
                    {"模块:", "模块讨论:"},
                    {"用户:", "用户讨论:"},
                    {"帮助:", "帮助讨论:"},
                    {"文件:", "文件讨论:"},
                    {"分类:", "分类讨论:"},
                    {"WikiKernel:", "WikiKernel讨论:"},
                    {"MediaWiki:", "MediaWiki讨论:"},
                    {"组件:", "组件讨论:"},
                    {"小部件:", "小部件讨论:"},
                    {"图表:", "图表讨论:"},
                    {"讨论:", "讨论:"} // 主命名空间讨论页不做二次转换
            };
            for (String[] pair : pairs) {
                if (value.startsWith(pair[0]) && !value.startsWith(pair[1])) {
                    return pair[1] + value.substring(pair[0].length());
                }
            }
            return null;
        }

        /** {@code mw.title} 对象（Scribunto 常用字段 / 方法）。 */
        static LuaTable titleTable(String title) {
            return titleTable(title, null);
        }

        static LuaTable titleTable(String title, Function<String, String> contentLookup) {
            return titleTable(title, contentLookup, true);
        }

        static LuaTable titleTable(String title, Function<String, String> contentLookup, boolean links) {
            String value = WikiTitles.normalize(title == null ? "" : title);
            String fragment = null;
            int hash = value.indexOf('#');
            if (hash >= 0) {
                fragment = value.substring(hash + 1);
                value = value.substring(0, hash);
            }
            int namespace = ActionApiNamespaces.idOf(value);
            String content = contentLookup == null ? null : contentLookup.apply(value);
            final String currentTitle = value;
            LuaTable table = new LuaTable();
            table.set("fullText", value.isEmpty() ? LuaValue.NIL : LuaValue.valueOf(value));
            table.set("prefixedText", value.isEmpty() ? LuaValue.NIL : LuaValue.valueOf(value));
            table.set("text", LuaValue.valueOf(ActionApiNamespaces.bareTitle(value)));
            table.set("nsText", LuaValue.valueOf(ActionApiNamespaces.nameOf(namespace)));
            table.set("namespace", LuaValue.valueOf(namespace));
            table.set("fragment", fragment == null ? LuaValue.NIL : LuaValue.valueOf(fragment));
            table.set("id", LuaValue.ZERO);
            table.set("exists", LuaValue.valueOf(content != null));
            table.set("isRedirect", LuaValue.valueOf(content != null
                    && content.stripLeading().toLowerCase(Locale.ROOT).startsWith("#redirect")));
            table.set("isSubpage", LuaValue.valueOf(isSubpage(value)));
            table.set("subpageText", LuaValue.valueOf(subpageText(value)));
            table.set("isTalkPage", LuaValue.valueOf(subjectTitle(value) != null));
            String talk = talkTitle(value);
            if (links && talk != null) {
                table.set("talkPageTitle", titleTable(talk, contentLookup, false));
            }
            String subject = subjectTitle(value);
            if (links && subject != null) {
                table.set("subjectPageTitle", titleTable(subject, contentLookup, false));
            }
            String base = links ? baseTitle(value) : null;
            if (base != null) {
                table.set("basePageTitle", titleTable(base, contentLookup, false));
            }
            String root = links ? rootTitle(value) : null;
            if (root != null) {
                table.set("rootPageTitle", titleTable(root, contentLookup, false));
            }
            table.set("getContent", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    String name = values.arg(1).get("fullText").optjstring("");
                    String pageContent = contentLookup == null ? null : contentLookup.apply(name);
                    return pageContent == null ? LuaValue.NIL : LuaValue.valueOf(pageContent);
                }
            });
            table.set("inNamespace", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    String name = values.arg(1).get("fullText").optjstring("");
                    String wanted = values.arg(2).optjstring("");
                    if (wanted.isBlank()) {
                        return LuaValue.FALSE;
                    }
                    int wantedId;
                    try {
                        wantedId = Integer.parseInt(wanted);
                    } catch (NumberFormatException exception) {
                        wantedId = ActionApiNamespaces.idOf(wanted + ":X");
                    }
                    return LuaValue.valueOf(ActionApiNamespaces.idOf(name) == wantedId);
                }
            });
            table.set("isProtected", LuaValue.FALSE);
            table.set("isIncorrect", LuaValue.FALSE);
            table.set("isContentPage", LuaValue.valueOf(namespace == 0));
            table.set("contentModel", LuaValue.valueOf("wikitext"));
            table.set("pageLanguage", LuaValue.valueOf("zh"));
            table.set("hasSubjectPage", LuaValue.valueOf(content != null));
            table.set("protectionLevels", new LuaTable());
            LuaTable cascading = new LuaTable();
            cascading.set("protection", new LuaTable());
            cascading.set("source", LuaValue.NIL);
            table.set("cascadingProtection", cascading);
            if (content != null) {
                java.util.regex.Matcher redirect = java.util.regex.Pattern
                        .compile("(?is)^\\s*#redirect\\s*\\[\\[\\s*([^\\]|#]+)")
                        .matcher(content);
                if (redirect.find()) {
                    table.set("redirectTarget", titleTable(redirect.group(1).trim(), contentLookup, false));
                }
            }
            table.set("getCurrentTitle", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return titleTable(currentTitle, contentLookup);
                }
            });
            VarArgFunction url = new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    String title = values.arg(1).get("fullText").optjstring("");
                    String query = values.arg(2).optjstring("");
                    String path = WikiUrls.pagePath(title);
                    if (!query.isBlank()) {
                        path = path + (path.contains("?") ? "&" : "?") + query;
                    }
                    return LuaValue.valueOf(path);
                }
            };
            table.set("fullUrl", url);
            table.set("localUrl", url);
            table.set("canonicalUrl", url);
            return table;
        }

        private static boolean isSubpage(String value) {
            int colon = value.indexOf(':');
            int slash = value.indexOf('/', colon + 1);
            return slash > 0 && slash < value.length() - 1;
        }

        private static String subpageText(String value) {
            int slash = value.lastIndexOf('/');
            return slash >= 0 && slash < value.length() - 1 ? value.substring(slash + 1)
                    : ActionApiNamespaces.bareTitle(value);
        }

        private static String baseTitle(String value) {
            int slash = value.lastIndexOf('/');
            return slash > 0 ? value.substring(0, slash) : null;
        }

        private static String rootTitle(String value) {
            int colon = value.indexOf(':');
            int start = colon >= 0 ? colon + 1 : 0;
            int slash = value.indexOf('/', start);
            if (slash <= start) {
                return null;
            }
            return value.substring(0, slash);
        }

        /** 讨论页 → 主题页；不是讨论页返回 null。 */
        private static String subjectTitle(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String[][] pairs = {
                    {"模板讨论:", "模板:"}, {"模块讨论:", "模块:"}, {"用户讨论:", "用户:"},
                    {"帮助讨论:", "帮助:"}, {"文件讨论:", "文件:"}, {"分类讨论:", "分类:"},
                    {"WikiKernel讨论:", "WikiKernel:"}, {"MediaWiki讨论:", "MediaWiki:"},
                    {"组件讨论:", "组件:"}, {"小部件讨论:", "小部件:"}, {"图表讨论:", "图表:"}
            };
            for (String[] pair : pairs) {
                if (value.startsWith(pair[0])) {
                    return pair[1] + value.substring(pair[0].length());
                }
            }
            return null;
        }

        // ==================== 工具 ====================

        private static void setArg(LuaTable table, String key, String value) {
            try {
                table.set(Integer.parseInt(key), LuaValue.valueOf(value));
            } catch (NumberFormatException exception) {
                table.set(key, LuaValue.valueOf(value));
            }
        }

        private static LuaTable argsTable(Map<String, String> values) {
            LuaTable table = new LuaTable();
            values.forEach((key, value) -> setArg(table, key, value));
            return table;
        }

        /** 把 Lua 字符串库的函数原样转发（mw.ustring 的 ASCII 兼容实现）。 */
        private static VarArgFunction forward(LuaValue function) {            return new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs values) {
                    return function.invoke(values);
                }
            };
        }

        /** 一元字符串函数：{@code nil} 视为空串。 */
        private static OneArgFunction oneArg(java.util.function.UnaryOperator<String> function) {
            return new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue value) {
                    String result = function.apply(value.isnil() ? null : value.tojstring());
                    return LuaValue.valueOf(result == null ? "" : result);
                }
            };
        }

        /** {@code mw.clone}：递归复制 Lua 表（保留标量 / 函数的引用）。 */
        static LuaValue cloneValue(LuaValue value, java.util.IdentityHashMap<LuaValue, LuaValue> seen) {
            if (value == null || !value.istable()) {
                return value;
            }
            LuaValue existing = seen.get(value);
            if (existing != null) {
                return existing;
            }
            LuaTable copy = new LuaTable();
            seen.put(value, copy);
            LuaValue key = LuaValue.NIL;
            while (true) {
                Varargs next = value.next(key);
                key = next.arg1();
                if (key.isnil()) {
                    break;
                }
                copy.set(cloneValue(key, seen), cloneValue(next.arg(2), seen));
            }
            return copy;
        }

        /** {@code mw.dumpObject}：把 Lua 值转成便于调试的文本。 */
        static String dumpValue(LuaValue value, java.util.IdentityHashMap<LuaValue, Boolean> seen, int depth) {
            if (value == null || value.isnil()) {
                return "nil";
            }
            if (value.isstring()) {
                return "\"" + value.tojstring() + "\"";
            }
            if (value.isnumber() || value.isboolean()) {
                return value.tojstring();
            }
            if (value.isfunction()) {
                return "function";
            }
            if (!value.istable()) {
                return value.typename();
            }
            if (seen.put(value, Boolean.TRUE) != null) {
                return "table (cycle)";
            }
            StringBuilder out = new StringBuilder("{");
            LuaValue key = LuaValue.NIL;
            boolean first = true;
            while (true) {
                Varargs next = value.next(key);
                key = next.arg1();
                if (key.isnil()) {
                    break;
                }
                if (!first) {
                    out.append(", ");
                }
                first = false;
                if (depth >= 4) {
                    out.append("...");
                    break;
                }
                out.append(dumpValue(key, seen, depth + 1)).append(" = ")
                        .append(dumpValue(next.arg(2), seen, depth + 1));
            }
            out.append('}');
            seen.remove(value);
            return out.toString();
        }

        /** {@code mw.text.decode}：HTML 实体解码。 */
        private static String decodeEntities(String value) {
            return value == null || value.indexOf('&') < 0
                    ? (value == null ? "" : value)
                    : Parser.unescapeEntities(value, false);
        }

        /** {@code mw.text.nowiki}：把 HTML / wikitext 特殊字符转义成可显示文本。 */
        private static String nowiki(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;")
                    .replace("[[", "&#91;&#91;").replace("]]", "&#93;&#93;")
                    .replace("{{", "&#123;&#123;").replace("}}", "&#125;&#125;")
                    .replace("|", "&#124;");
        }

        /** {@code mw.text.split}：按纯文本分隔符拆分（保留空段）。 */
        private static LuaValue splitText(String text, String separator) {
            LuaTable result = new LuaTable();
            if (separator == null || separator.isEmpty()) {
                result.set(1, LuaValue.valueOf(text == null ? "" : text));
                return result;
            }
            String[] pieces = (text == null ? "" : text)
                    .split(java.util.regex.Pattern.quote(separator), -1);
            for (int i = 0; i < pieces.length; i++) {
                result.set(i + 1, LuaValue.valueOf(pieces[i]));
            }
            return result;
        }

        /** {@code mw.text.listToText}：把数组表按分隔符连接。 */
        private static String listToText(LuaValue list, String separator) {
            if (list == null || !list.istable()) {
                return "";
            }
            LuaTable table = list.checktable();
            String sep = separator == null || separator.isEmpty() ? ", " : separator;
            StringBuilder out = new StringBuilder();
            for (int i = 1; ; i++) {
                LuaValue value = table.get(i);
                if (value.isnil()) {
                    break;
                }
                if (out.length() > 0) {
                    out.append(sep);
                }
                out.append(value.tojstring());
            }
            return out.toString();
        }

        /** {@code mw.text.truncate}：按 Unicode 码点截断，默认追加省略号。 */
        private static String truncate(String value, int length, String ellipsis) {
            String source = value == null ? "" : value;
            if (length <= 0 || source.codePointCount(0, source.length()) <= length) {
                return source;
            }
            int end = source.offsetByCodePoints(0, length);
            return source.substring(0, end) + (ellipsis == null ? "" : ellipsis);
        }

        /** {@code mw.text.plain}：去除 HTML 标签并解码实体（保留空白结构）。 */
        private static String plain(String value) {            if (value == null) {
                return "";
            }
            String text = value.replaceAll("(?s)<[^>]*>", "");
            if (text.indexOf('&') >= 0) {
                text = Parser.unescapeEntities(text, false);
            }
            return text;
        }

        /** {@code mw.uri.decode}：解码 {@code %XX}（不把 {@code +} 当空格）。 */
        private static String decodePercent(String value) {
            if (value == null || value.isEmpty() || value.indexOf('%') < 0) {
                return value == null ? "" : value;
            }
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        }
    }
}
