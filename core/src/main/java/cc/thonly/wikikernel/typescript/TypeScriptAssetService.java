package cc.thonly.wikikernel.typescript;

import cc.thonly.wikikernel.common.data.WikiCompiledScript;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.config.TypeScriptProperties;
import cc.thonly.wikikernel.db.mapper.WikiCompiledScriptMapper;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 编译产物服务：把「源码页 → 编译结果」串起来，并管理缓存。
 * <ul>
 *     <li>{@link #compile(WikiPage, String)}：命中缓存直接返回；未命中才真正编译并落库；</li>
 *     <li>缓存键 = 页面 + 版本 + 目标 + 编译器版本 → 每次保存自动失效，不会发旧代码；</li>
 *     <li>每个「页面 + 目标」只保留最新一条，并按 {@code cache-limit} 做总量裁剪。</li>
 * </ul>
 */
@Service
public class TypeScriptAssetService {

    private static final Logger log = LoggerFactory.getLogger(TypeScriptAssetService.class);

    /** 目标形态：JavaScript。 */
    public static final String TARGET_JAVASCRIPT = "javascript";

    /**
     * 站点设置键：单次编译的源码大小上限（KB）。
     * <b>≤ 0（例如 -1）表示不限制</b>；留空 = 用 {@code wikikernel.typescript.max-source-kilobytes} 里的值。
     * 可在「特殊:权限配置 → 站点设置」里随时改，立即生效（无需重启）。
     */
    public static final String MAX_SOURCE_KB_KEY = "settings.typescript.max-source-kilobytes";

    /** 正在进行中的编译（键 = 版本 + 目标）：同一版本的并发请求共享一次编译。 */
    private final ConcurrentHashMap<String, CompletableFuture<WikiCompiledScript>> inFlight =
            new ConcurrentHashMap<>();

    private final TypeScriptCompiler compiler;
    private final WikiCompiledScriptMapper mapper;
    private final FeatureService featureService;
    private final TypeScriptProperties properties;
    private final SiteSettingsService siteSettingsService;

    public TypeScriptAssetService(TypeScriptCompiler compiler,
                                 WikiCompiledScriptMapper mapper,
                                 FeatureService featureService,
                                 TypeScriptProperties properties,
                                 SiteSettingsService siteSettingsService) {
        this.compiler = compiler;
        this.mapper = mapper;
        this.featureService = featureService;
        this.properties = properties;
        this.siteSettingsService = siteSettingsService;
    }

    /** 编译功能是否可用（总开关开 + 编译器内置）。 */
    public boolean enabled() {
        return featureService.typescriptEnabled() && compiler.available();
    }

    /**
     * 生效的源码大小上限（KB，<b>≤ 0 = 不限制</b>）：
     * 站点设置（运行时可改）优先，其次 {@code wikikernel.typescript.max-source-kilobytes}（YAML），最后默认值。
     */
    public int maxSourceKilobytes() {
        return normalizeMaxSourceKilobytes(
                siteSettingsService.get(MAX_SOURCE_KB_KEY, ""), properties.getMaxSourceKilobytes());
    }

    /**
     * 解析 / 规范化「源码大小上限」设置值：数字原样返回（可为 -1 = 不限制），
     * 空或非法值回退到 YAML 默认值（便于「清空输入框 = 用配置文件的值」）。
     */
    public static int normalizeMaxSourceKilobytes(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value > 1_000_000L) {
                return 1_000_000;
            }
            if (value < -1L) {
                return -1;
            }
            return (int) value;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** 上限的展示文本（设置页只读摘要用）：{@code 256 KB} 或 {@code 不限制}。 */
    public String maxSourceKilobytesText() {
        int limit = maxSourceKilobytes();
        return limit <= 0 ? "不限制" : limit + " KB";
    }

    /** 是否是服务端能编译的源码页面（{@code .ts} / {@code .tsx}）。 */
    public static boolean isTypeScriptPage(String title) {
        String lower = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        if (isDeclarationPage(lower)) {
            return false;
        }
        return lower.endsWith(".ts") || lower.endsWith(".tsx");
    }

    /**
     * 是否是 **TypeScript 类型声明页**（{@code .d.ts} / {@code .d.tsx} / {@code .d.mts} / {@code .d.cts}）。
     * <p>
     * 声明文件只有类型、没有运行时代码，所以：<b>不</b>参与服务端编译（编译产物是空的），
     * <b>不</b>会被当成页面脚本 / Gadget 资源自动加载；但仍按代码页做语法高亮。
     * （注意 {@code "x.d.ts".endsWith(".ts")} 为真，必须先判声明文件再判 {@code .ts}。）
     */
    public static boolean isDeclarationPage(String title) {
        String lower = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".d.ts") || lower.endsWith(".d.tsx")
                || lower.endsWith(".d.mts") || lower.endsWith(".d.cts");
    }

    /** 该页面在第 {@code revisionId} 版是否已有缓存（不触发编译）。 */
    public Optional<WikiCompiledScript> cached(long revisionId, String target) {
        if (revisionId <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<WikiCompiledScript>lambdaQuery()
                .eq(WikiCompiledScript::getRevisionId, revisionId)
                .eq(WikiCompiledScript::getTarget, target)
                .orderByDesc(WikiCompiledScript::getId)
                .last("LIMIT 1")));
    }

    /**
     * 取编译结果：命中缓存直接返回；未命中（或编译器版本变了）就编译并写入缓存。
     * 编译失败也会落库（{@code successful = false} + 诊断），避免每次请求都重试同一份坏源码。
     * <p>
     * <b>同版本并发去重</b>：多个请求同时要同一版本（例如脚本被多个页面引用、或一屏内多次请求）时只编译一次，
     * 其余请求共享同一个结果——引擎池默认只有 1 个上下文，这一步能显著减少排队。
     */
    public WikiCompiledScript compile(WikiPage page, String source, String target) {
        if (page == null || page.getLatestRevisionId() == null) {
            return failed(null, "页面没有版本，无法编译");
        }
        Optional<WikiCompiledScript> hit = cached(page.getLatestRevisionId(), target);
        if (hit.isPresent() && compiler.version().equals(hit.get().getCompiler())) {
            return hit.get();
        }
        String key = page.getLatestRevisionId() + "|" + target;
        CompletableFuture<WikiCompiledScript> mine = new CompletableFuture<>();
        CompletableFuture<WikiCompiledScript> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            try {
                // 等待别人正在做的那一次编译（超时留一点余量，避免请求被无限拖住）
                return running.get(properties.getTimeoutSeconds() + properties.getInitTimeoutSeconds() + 5L,
                        TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                return failed(page.getId(), "编译进行中（等待超时），请稍后刷新");
            } catch (ExecutionException exception) {
                return failed(page.getId(), "编译失败：" + exception.getCause());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return failed(page.getId(), "编译被中断");
            }
        }
        try {
            WikiCompiledScript row = doCompile(page, source, target);
            mine.complete(row);
            return row;
        } catch (RuntimeException exception) {
            mine.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(key);
        }
    }

    /** 真正编译 + 落库（调用方负责去重）。 */
    private WikiCompiledScript doCompile(WikiPage page, String source, String target) {
        // 源码大小上限（站点设置可动态改，≤ 0 = 不限制）：超限**不写缓存**，
        // 这样之后把上限调大（或改成 -1）就能立刻重新编译，不会被一次拒绝钉死
        int limitKb = maxSourceKilobytes();
        if (limitKb > 0 && source != null && source.length() > (long) limitKb * 1024) {
            return failed(page.getId(), "源码过大（" + (source.length() / 1024) + " KB > 上限 " + limitKb
                    + " KB）：可在「特殊:权限配置 → 站点设置 → TypeScript 服务端编译」调整上限，"
                    + "-1 表示不限制。");
        }
        String fileName = fileNameOf(page.getTitle());
        TypeScriptCompiler.Result result = compiler.compile(source, fileName);
        WikiCompiledScript row = new WikiCompiledScript();
        row.setPageId(page.getId());
        row.setRevisionId(page.getLatestRevisionId());
        row.setTarget(target);
        row.setCompiler(compiler.version());
        row.setSuccessful(result.ok());
        row.setCode(result.ok() ? result.code() : "");
        row.setDiagnostics(result.ok() ? "" : result.diagnosticsText());
        row.setCreateAt(LocalDateTime.now());
        try {
            mapper.insert(row);
            purgeOld(page.getId(), target, row.getId());
        } catch (RuntimeException exception) {
            log.warn("写入编译缓存失败：{}", exception.getMessage());
        }
        return row;
    }

    /** 保存页面后按需编译（只在「保存时编译」开启时调用）。 */
    public Optional<WikiCompiledScript> compileAfterSave(WikiPage page, String source) {
        if (!enabled() || !featureService.typescriptCompileOnSave() || page == null) {
            return Optional.empty();
        }
        return Optional.of(compile(page, source, TARGET_JAVASCRIPT));
    }

    // ==================== 内部 ====================

    /** 只保留该页面 + 目标的最新一条，并按配置裁剪总量。 */
    private void purgeOld(long pageId, String target, long keepId) {
        mapper.delete(Wrappers.<WikiCompiledScript>lambdaQuery()
                .eq(WikiCompiledScript::getPageId, pageId)
                .eq(WikiCompiledScript::getTarget, target)
                .ne(WikiCompiledScript::getId, keepId));
        int limit = Math.max(20, properties.getCacheLimit());
        long total = mapper.selectCount(null);
        if (total <= limit) {
            return;
        }
        List<WikiCompiledScript> stale = mapper.selectList(Wrappers.<WikiCompiledScript>lambdaQuery()
                .orderByAsc(WikiCompiledScript::getId)
                .last("LIMIT " + (total - limit)));
        for (WikiCompiledScript row : stale) {
            mapper.deleteById(row.getId());
        }
    }

    private WikiCompiledScript failed(Long pageId, String message) {
        WikiCompiledScript row = new WikiCompiledScript();
        row.setPageId(pageId);
        row.setRevisionId(0L);
        row.setTarget(TARGET_JAVASCRIPT);
        row.setCompiler(compiler.version());
        row.setSuccessful(false);
        row.setCode("");
        row.setDiagnostics(message);
        row.setCreateAt(LocalDateTime.now());
        return row;
    }

    private static String fileNameOf(String title) {
        String value = title == null ? "page.ts" : title;
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash >= 0 ? value.substring(slash + 1) : value;
    }
}
