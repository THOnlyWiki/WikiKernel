package cc.thonly.wikikernel.lua;

import cc.thonly.wikikernel.actionapi.ActionApiNamespaces;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.web.WikiUrls;
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
                            LuaSiteInfoProvider siteInfoProvider) {
        this.wikiPageService = wikiPageService;
        this.messageSource = messageSource;
        this.siteInfoProvider = siteInfoProvider;
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
        List<String> candidates = raw.indexOf(':') >= 0
                ? List.of(raw)
                : MODULE_PREFIXES.stream().map(prefix -> prefix + raw).toList();
        for (String title : candidates) {
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
                context.pageTitle, compiled.htmlLib(), siteInfoProvider, messageSource, loadDataCall));
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
        if (found.isEmpty()) {
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
            frame.set("args", args);
            frame.set("getParent", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    if (parentArgs == null) {
                        return LuaValue.NIL;
                    }
                    LuaTable parent = new LuaTable();
                    parent.set("args", argsTable(parentArgs));
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
                    return LuaValue.valueOf(extensionTag(values));
                }
            });
            return frame;
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
        private static String extensionTag(Varargs values) {
            String name;
            String content = "";
            LuaTable attributes = null;
            if (values.arg(1).istable()) {
                LuaTable spec = values.arg(1).checktable();
                name = spec.get("name").optjstring("");
                content = spec.get("content").optjstring("");
                if (spec.get("args").istable()) {
                    attributes = spec.get("args").checktable();
                }
            } else {
                name = values.arg(1).optjstring("");
                content = values.arg(2).optjstring("");
                if (values.arg(3).istable()) {
                    attributes = values.arg(3).checktable();
                }
            }
            if (name.isBlank() || "templatestyles".equalsIgnoreCase(name)) {
                return "";
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
                           Function<String, LuaValue> loadDataCall) {
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
            mw.set("text", text);

            LuaTable ustring = new LuaTable();
            LuaValue stringLib = globals.get("string");
            ustring.set("find", forward(stringLib.get("find")));
            ustring.set("match", forward(stringLib.get("match")));
            ustring.set("gmatch", forward(stringLib.get("gmatch")));
            ustring.set("gsub", forward(stringLib.get("gsub")));
            ustring.set("rep", forward(stringLib.get("rep")));
            ustring.set("format", forward(stringLib.get("format")));
            ustring.set("byte", forward(stringLib.get("byte")));
            ustring.set("char", forward(stringLib.get("char")));
            ustring.set("lower", oneArg(value -> value == null ? "" : value.toLowerCase(Locale.ROOT)));
            ustring.set("upper", oneArg(value -> value == null ? "" : value.toUpperCase(Locale.ROOT)));
            ustring.set("len", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue value) {
                    String source = value.isnil() ? "" : value.tojstring();
                    return LuaValue.valueOf(source.codePointCount(0, source.length()));
                }
            });
            ustring.set("sub", new ThreeArgFunction() {
                @Override
                public LuaValue call(LuaValue value, LuaValue start, LuaValue end) {
                    String source = value.tojstring();
                    int length = source.codePointCount(0, source.length());
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
                    if (from > to) {
                        return LuaValue.valueOf("");
                    }
                    int startOffset = source.offsetByCodePoints(0, from - 1);
                    int endOffset = source.offsetByCodePoints(0, to);
                    return LuaValue.valueOf(source.substring(startOffset, endOffset));
                }
            });
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
                    return titleTable(text);
                }
            });
            title.set("getCurrentTitle", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return titleTable(pageTitle);
                }
            });
            mw.set("title", title);

            LuaTable uri = new LuaTable();
            uri.set("encode", oneArg(value -> URLEncoder.encode(value == null ? "" : value,
                    StandardCharsets.UTF_8).replace("+", "%20")));
            uri.set("anchorEncode", oneArg(value -> URLEncoder.encode(value == null ? "" : value,
                    StandardCharsets.UTF_8).replace("+", "_")));
            uri.set("decode", oneArg(value -> decodePercent(value == null ? "" : value)));
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

            // mw.site：站点信息 + 统计 + 命名空间
            mw.set("site", site(siteInfo));

            // mw.message：界面消息
            mw.set("message", messageLibrary(messageSource));
            return mw;
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
            Locale locale = LocaleContextHolder.getLocale();
            for (ActionApiNamespaces.Ns ns : ActionApiNamespaces.all()) {
                LuaTable entry = new LuaTable();
                entry.set("id", LuaValue.valueOf(ns.id()));
                entry.set("name", LuaValue.valueOf(ActionApiNamespaces.displayName(ns.id(), locale)));
                entry.set("canonicalName", LuaValue.valueOf(ns.name()));
                namespaces.set(ns.id(), entry);
            }
            site.set("namespaces", namespaces);
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
                    params.addAll(paramsFrom(args, 1));
                    return message;
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

        /** {@code mw.title} 对象（MVP：fullText / text / nsText / namespace / fragment / id）。 */
        static LuaTable titleTable(String title) {
            String value = WikiTitles.normalize(title == null ? "" : title);
            String fragment = null;
            int hash = value.indexOf('#');
            if (hash >= 0) {
                fragment = value.substring(hash + 1);
                value = value.substring(0, hash);
            }
            int namespace = ActionApiNamespaces.idOf(value);
            LuaTable table = new LuaTable();
            table.set("fullText", value.isEmpty() ? LuaValue.NIL : LuaValue.valueOf(value));
            table.set("prefixedText", value.isEmpty() ? LuaValue.NIL : LuaValue.valueOf(value));
            table.set("text", LuaValue.valueOf(ActionApiNamespaces.bareTitle(value)));
            table.set("nsText", LuaValue.valueOf(ActionApiNamespaces.nameOf(namespace)));
            table.set("namespace", LuaValue.valueOf(namespace));
            table.set("fragment", fragment == null ? LuaValue.NIL : LuaValue.valueOf(fragment));
            table.set("id", LuaValue.ZERO);
            String talk = talkTitle(value);
            if (talk != null) {
                table.set("talkPageTitle", titleTable(talk));
            }
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

        /** {@code mw.text.plain}：去除 HTML 标签并解码实体（保留空白结构）。 */
        private static String plain(String value) {
            if (value == null) {
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
