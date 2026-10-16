package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiBlock;
import cc.thonly.wikikernel.common.data.WikiCompiledScript;
import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiFileVersion;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.EditFormContext;
import cc.thonly.wikikernel.common.extension.FileEmbedCall;
import cc.thonly.wikikernel.common.extension.FileEmbedRenderer;
import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.common.extension.PageViewEvent;
import cc.thonly.wikikernel.common.extension.PageViewHook;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.PluginHeadAdvice;
import cc.thonly.wikikernel.http.RequestMetadata;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.EditHookService;
import cc.thonly.wikikernel.service.EmailBlacklistService;
import cc.thonly.wikikernel.service.EmailWhitelistService;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.InterwikiService;
import cc.thonly.wikikernel.service.PageBody;
import cc.thonly.wikikernel.service.PageBodyRenderer;
import cc.thonly.wikikernel.service.PageCacheService;
import cc.thonly.wikikernel.service.PageCategoryService;
import cc.thonly.wikikernel.service.PageDisplayHookService;
import cc.thonly.wikikernel.service.PageLinkService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.SiteNameService;
import cc.thonly.wikikernel.service.SpecialPageMatch;
import cc.thonly.wikikernel.service.SpecialWikiPageService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.TitleBlacklistService;
import cc.thonly.wikikernel.typescript.TypeScriptAssetService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WatchService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.skin.SkinRegistry;
import cc.thonly.wikikernel.special.BlockSpecialPage;
import cc.thonly.wikikernel.special.FileViewMapper;
import cc.thonly.wikikernel.special.PageInfoSpecialPage;
import cc.thonly.wikikernel.special.UserToolsSidebar;
import cc.thonly.wikikernel.time.WikiTime;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.CodePages;
import cc.thonly.wikikernel.web.ConfigPages;
import cc.thonly.wikikernel.web.FileTitles;
import cc.thonly.wikikernel.web.WikiFileEmbedCall;
import cc.thonly.wikikernel.web.RequestEditContext;
import cc.thonly.wikikernel.web.RequestHeadContext;
import cc.thonly.wikikernel.web.SectionEditLinks;
import cc.thonly.wikikernel.web.TalkPages;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.web.WikiPageDisplayContext;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.wikitext.CssSanitizer;
import cc.thonly.wikikernel.wikitext.DiffRows;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;
import cc.thonly.wikikernel.wikitext.WikiRedirect;
import cc.thonly.wikikernel.wikitext.WikiSections;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Wiki 页面入口。
 * <p>
 * 路由（仿 MediaWiki 的 action 参数）：
 * <ul>
 *     <li>{@code GET /wiki/{title}}：阅读最新版本；{@code ?oldid=123} 查看指定版本；</li>
 *     <li>{@code GET /wiki/{title}?action=edit}：编辑（未登录先跳登录页，未创建的页面可直接创建）；</li>
 *     <li>{@code GET /wiki/{title}?action=history}：版本历史；</li>
 *     <li>{@code POST /wiki/{title}}：保存编辑（PRG 重定向回页面）。</li>
 * </ul>
 * 特殊页（{@code 特殊:XXX}）走注册表，不受这些 action 影响。
 */
@Controller
public class WikiPageController {
    /** 默认主页面标题（中文）；其它语言用消息键 {@code mainPage} 指定本地化标题。 */
    private static final String DEFAULT_MAIN_PAGE = "首页";

    /** #REDIRECT 链最多解析跳数（超过按普通页面渲染，防止死循环）。 */
    private static final int MAX_REDIRECTS = 5;

    /** 用户命名空间前缀（大小写不敏感）。 */
    private static final List<String> USER_NAMESPACE_PREFIXES = List.of("用户:", "User:");

    /** 用户讨论命名空间前缀（工具侧栏同样生效）。 */
    private static final List<String> USER_TALK_PREFIXES = List.of("用户讨论:", "User talk:");

    /** 分类页最多列出多少成员。 */
    private static final int CATEGORY_MEMBER_LIMIT = 500;

    private final WikiPageService wikiPageService;
    private final SpecialWikiPageService specialWikiPageService;
    /** 页面正文缓存（渲染后的正文 HTML；未命中时渲染并写回）+ 无缓存渲染（?oldid= 看旧版本用）。 */
    private final PageCacheService pageCacheService;
    private final PageBodyRenderer pageBodyRenderer;
    private final PluginManager pluginManager;
    private final UserService userService;
    private final PermissionService permissionService;
    private final AccessControlService accessControlService;
    private final SiteSettingsService siteSettingsService;
    private final PageCategoryService pageCategoryService;
    private final PageLinkService pageLinkService;
    private final UserToolsSidebar userToolsSidebar;
    private final FileService fileService;
    private final FileViewMapper fileViewMapper;
    private final BlockService blockService;
    private final FeatureService featureService;
    /** 用户页资料卡闸门（插件声明；没有插件时 true）。 */
    private final cc.thonly.wikikernel.service.UserProfileHookService userProfileHooks;
    private final TitleBlacklistService titleBlacklistService;
    private final EmailWhitelistService emailWhitelistService;
    private final EmailBlacklistService emailBlacklistService;
    private final WatchService watchService;
    /** 编辑钩子调度：插件的保存前校验（{@code EditGuard}）与编辑页表单字段（{@code EditFormField}）。 */
    private final EditHookService editHookService;
    /** 页面显示钩子调度：插件的正文片段（{@code PageDisplayHook}：提示条 / 页面横幅 / 评论区 / 认领整页）。 */
    private final PageDisplayHookService pageDisplayHookService;
    /** TypeScript 服务端编译（{@code .ts} / {@code .tsx} 页面 → JavaScript）。 */
    private final TypeScriptAssetService typeScriptAssetService;
    /** TypeScript 编译器（把版本号写进 ETag：编译器升级后浏览器会自动拿到新产物）。 */
    private final cc.thonly.wikikernel.typescript.TypeScriptCompiler typeScriptCompiler;
    private final MessageSource messageSource;
    /** 皮肤注册表：取「本次请求真正生效的皮肤」，用于按皮肤注入站点 CSS / JS。 */
    private final SkinRegistry skinRegistry;

    public WikiPageController(WikiPageService wikiPageService,
                              SpecialWikiPageService specialWikiPageService,
                              PageCacheService pageCacheService,
                              PageBodyRenderer pageBodyRenderer,
                              PluginManager pluginManager,
                              UserService userService,
                              PermissionService permissionService,
                              AccessControlService accessControlService,
                              SiteSettingsService siteSettingsService,
                              PageCategoryService pageCategoryService,
                              PageLinkService pageLinkService,
                              UserToolsSidebar userToolsSidebar,
                              FileService fileService,
                              FileViewMapper fileViewMapper,
                              BlockService blockService,
                              FeatureService featureService,
    cc.thonly.wikikernel.service.UserProfileHookService userProfileHooks,
                              TitleBlacklistService titleBlacklistService,
                              EmailWhitelistService emailWhitelistService,
                              EmailBlacklistService emailBlacklistService,
                              WatchService watchService,
                              EditHookService editHookService,
                              PageDisplayHookService pageDisplayHookService,
                              TypeScriptAssetService typeScriptAssetService,
                              cc.thonly.wikikernel.typescript.TypeScriptCompiler typeScriptCompiler,
                              MessageSource messageSource,
                              SkinRegistry skinRegistry) {
        this.wikiPageService = wikiPageService;
        this.specialWikiPageService = specialWikiPageService;
        this.pageCacheService = pageCacheService;
        this.pageBodyRenderer = pageBodyRenderer;
        this.pluginManager = pluginManager;
        this.userService = userService;
        this.permissionService = permissionService;
        this.accessControlService = accessControlService;
        this.siteSettingsService = siteSettingsService;
        this.pageCategoryService = pageCategoryService;
        this.pageLinkService = pageLinkService;
        this.userToolsSidebar = userToolsSidebar;
        this.fileService = fileService;
        this.fileViewMapper = fileViewMapper;
        this.blockService = blockService;
        this.featureService = featureService;
        this.userProfileHooks = userProfileHooks;
        this.titleBlacklistService = titleBlacklistService;
        this.emailWhitelistService = emailWhitelistService;
        this.emailBlacklistService = emailBlacklistService;
        this.watchService = watchService;
        this.editHookService = editHookService;
        this.pageDisplayHookService = pageDisplayHookService;
        this.typeScriptAssetService = typeScriptAssetService;
        this.typeScriptCompiler = typeScriptCompiler;
        this.messageSource = messageSource;
        this.skinRegistry = skinRegistry;
    }

    /**
     * 本次请求真正生效的皮肤 id（与模板里的 {@code ${skin.id}} 同一来源），
     * 用于按皮肤注入站点样式 / 脚本：{@code ?useskin=} 预览 → 登录用户偏好 → 站点默认 → fallback。
     */
    private String activeSkinId(HttpServletRequest request) {
        return this.skinRegistry.active(request).id();
    }

    /** 没有 {@code HttpServletRequest} 的场景（如差异对比页）：没有 {@code ?useskin=} 预览，按用户偏好 → 站点默认。 */
    private String activeSkinId(WikiUser user) {
        return this.skinRegistry.active(null, user).id();
    }

    @GetMapping("/")
    public String viewByIndex() {
        return "redirect:" + WikiUrls.pagePath(mainPageTitle());
    }

    /**
     * 本地化主页面标题（消息键 {@code mainPage}）：
     * 中文为「首页」、英文为「Main Page」；若该语言的首页尚未创建，回退到默认「首页」，避免根路径 404。
     */
    private String mainPageTitle() {
        String localized = messageSource.getMessage("mainPage", null, DEFAULT_MAIN_PAGE,
                LocaleContextHolder.getLocale());
        if (!Objects.equals(localized, DEFAULT_MAIN_PAGE)
                && this.wikiPageService.getVisibleByTitle(localized).isEmpty()) {
            return DEFAULT_MAIN_PAGE;
        }
        return localized;
    }

    /** 页面路由：标题可以带 /（子页面），第二段通配把剩余路径并回标题。 */
    @GetMapping({"/wiki/{title}", "/wiki/{title}/{*rest}"})
    public ModelAndView viewByTitle(@PathVariable("title") String title,
                                    @PathVariable(value = "rest", required = false) String rest,
                                    @RequestParam Map<String, String> parameters,
                                    HttpServletRequest request) {
        String requestedTitle = fullTitle(title, rest);
        String fullTitle = WikiTitles.normalize(requestedTitle);
        // 非法字符 / 过长 / 相对路径等：直接 400（仿 MediaWiki 的「标题无效」错误页）
        WikiTitles.validate(requestedTitle).ifPresent(error -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, titleErrorMessage(error));
        });
        if (this.specialWikiPageService.isSpecialPage(fullTitle)) {
            // 特殊页也支持 ?action=info（页面信息里显示特殊页的基本信息 / 链入数）
            if ("info".equals(parameters.getOrDefault("action", ""))) {
                return renderPageInfo(fullTitle, parameters, request);
            }
            return this.specialWikiPageService.resolve(fullTitle, parameters)
                    .map(match -> renderSpecialPage(match, request))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            message("error.specialPageMissing", fullTitle)));
        }
        // 分类别名 Category:X → 规范名 分类:X（保留 action 等查询参数）
        if (WikiLinkScanner.isCategoryTitle(requestedTitle)
                && !WikiLinkScanner.canonicalCategoryTitle(requestedTitle).equals(requestedTitle)) {
            String query = request.getQueryString();
            return new ModelAndView("redirect:" + WikiUrls.pagePath(
                    WikiLinkScanner.canonicalCategoryTitle(fullTitle))
                    + (Strings.isBlank(query) ? "" : "?" + query));
        }
        // 文件别名 File:X / Image:X → 规范名 文件:X（保留 action 等查询参数）
        if (FileTitles.isFileTitle(requestedTitle)
                && !FileTitles.titleOf(FileTitles.fileNameOf(requestedTitle)).equals(requestedTitle)) {
            String query = request.getQueryString();
            return new ModelAndView("redirect:" + WikiUrls.pagePath(
                    FileTitles.titleOf(FileTitles.fileNameOf(fullTitle)))
                    + (Strings.isBlank(query) ? "" : "?" + query));
        }
        // 可视化编辑入口（仿 MediaWiki 的 URL 参数）：
        //   ?veaction=edit       → 可视化编辑（不可用时与 MediaWiki 一样回退到源代码编辑）
        //   ?veaction=editsource → 源代码编辑
        String veaction = Strings.trimToEmpty(parameters.get("veaction"));
        if ("edit".equals(veaction) || "editsource".equals(veaction)) {
            WikiPage vePage = this.wikiPageService.getByTitle(fullTitle).orElse(null);
            WikiUser veUser = UserSession.current(request);
            if ("edit".equals(veaction) && visualEditorEligible(fullTitle, vePage, veUser)) {
                return renderEdit(fullTitle, parameters, request, true);
            }
            Long oldId = parseId(parameters.get("oldid"));
            return new ModelAndView("redirect:" + WikiUrls.pagePath(fullTitle) + "?action=edit"
                    + (oldId == null ? "" : "&oldid=" + oldId));
        }
        String action = parameters.getOrDefault("action", "view");
        return switch (action) {
            case "edit" -> renderEdit(fullTitle, parameters, request);
            case "history" -> renderHistory(fullTitle, request);
            case "info" -> renderPageInfo(fullTitle, parameters, request);
            default -> renderView(fullTitle, parameters, request);
        };
    }

    /** 可视化编辑是否可用：页面已存在、可编辑、且是 Wikitext 页面（代码页 / 微件页走源代码编辑）。 */
    private boolean visualEditorEligible(String title, WikiPage page, WikiUser user) {
        return page != null
                && codeLanguage(title) == null
                && canEditPage(title, page, user);
    }

    /**
     * 页面信息（仿 MediaWiki 的 {@code ?action=info}）：复用 {@code 特殊:页面信息} 的数据与模板；
     * 重定向页不跳转，直接显示重定向页自身的信息。
     */
    private ModelAndView renderPageInfo(String title, Map<String, String> parameters, HttpServletRequest request) {
        Map<String, String> infoParameters = new LinkedHashMap<>(parameters);
        infoParameters.put(PageInfoSpecialPage.PARAM_PAGE, title);
        // 页面名本身（数据，不是文案）：既用于解析特殊页，也作为「特殊页面不存在」的参数
        String infoPageTitle = "特殊:页面信息";
        return this.specialWikiPageService.resolve(infoPageTitle, infoParameters)
                .map(match -> renderSpecialPage(match, request))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        message("error.specialPageMissing", infoPageTitle)));
    }

    /**
     * 原样输出最新可见版本内容：{@code ?action=raw&ctype=text/javascript}。
     * 供站点/页面脚本（{@code <script src>}）与自定义样式加载；ctype 默认 text/plain。
     * <p>
     * {@code .ts} / {@code .tsx} 页面在请求 JavaScript 时返回**服务端编译结果**（按版本缓存）：
     * 未开启 TypeScript 编译、或编译失败时返回一段说明用的 JS 注释，避免浏览器报语法错误。
     * 不带 JS 的 ctype 时仍返回 TypeScript 源码，便于编辑与审计。
     * <p>
     * 响应带 <b>ETag（页面版本号）</b>与 {@code Cache-Control: no-cache}：浏览器每次会校验，
     * 页面一改就立刻拿到新内容，没改则走 304 —— 这是 {@code mw.wikiImport()} 模块加载正确性的前提
     * （模块 URL 不能带每次变化的查询参数，否则同一模块会被实例化多次）。
     * <p>
     * {@code &module=1}（由 {@code mw.wikiImport()} 添加）：请求方是 **ES 模块加载器**，
     * 编译失败时返回 <b>500 + 诊断文本</b>（让 {@code import()} 立刻失败并给出可读原因），
     * 而不是像经典脚本那样返回说明性注释（那会静默变成空模块）。
     */
    @GetMapping(value = {"/wiki/{title}", "/wiki/{title}/{*rest}"}, params = "action=raw")
    public ResponseEntity<String> rawContent(@PathVariable("title") String title,
                                             @PathVariable(value = "rest", required = false) String rest,
                                             @RequestParam Map<String, String> parameters,
                                             HttpServletRequest request) {
        String fullTitle = WikiTitles.normalize(fullTitle(title, rest));
        WikiPage page = this.wikiPageService.getVisibleByTitle(fullTitle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        message("error.pageMissing", fullTitle)));
        requireRead(fullTitle, UserSession.current(request));
        String content = this.wikiPageService.getLatestContent(page.getId()).orElse("");
        MediaType mediaType = rawMediaType(parameters.get("ctype"));
        boolean module = "1".equals(parameters.get("module"));
        if (isScriptMediaType(mediaType) && TypeScriptAssetService.isDeclarationPage(fullTitle)) {
            // 类型声明文件（x.d.ts）没有运行时代码：明确说明，避免浏览器报语法错误
            return rawResponse(page, etagOf(page, "declaration"), mediaType,
                    "/* 这是 TypeScript 类型声明文件（" + page.getTitle()
                            + "），只用于类型提示，没有可执行的运行时代码。 */");
        }
        if (isScriptMediaType(mediaType) && TypeScriptAssetService.isTypeScriptPage(fullTitle)) {
            if (!this.typeScriptAssetService.enabled()) {
                String comment = "/* TypeScript 编译未启用（站点设置 → TypeScript 服务端编译）；"
                        + "浏览器无法直接执行 TypeScript 源码。*/"
                        + System.lineSeparator() + "/* 源码：" + page.getTitle() + "?action=raw */";
                if (module) {
                    // 模块加载器不会执行注释，只会得到一个空模块：直接失败并说明原因
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.TEXT_PLAIN)
                            .body("TypeScript 编译未启用（站点设置 → TypeScript 服务端编译），无法作为模块加载："
                                    + page.getTitle());
                }
                return rawResponse(page, etagOf(page, "disabled"), mediaType, comment);
            }
            String etag = etagOf(page, "compiled:" + this.typeScriptCompiler.version());
            if (notModified(request, etag)) {
                // 命中校验：不编译、不查缓存表，直接 304
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag)
                        .cacheControl(CacheControl.noCache()).build();
            }
            WikiCompiledScript compiled = this.typeScriptAssetService.compile(
                    page, content, TypeScriptAssetService.TARGET_JAVASCRIPT);
            if (Boolean.TRUE.equals(compiled.getSuccessful())) {
                return rawResponse(page, etag, mediaType,
                        compiled.getCode() == null ? "" : compiled.getCode());
            }
            String diagnostics = compiled.getDiagnostics() == null ? "" : compiled.getDiagnostics();
            if (module) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("TypeScript 编译失败（" + page.getTitle() + "，版本 "
                                + page.getLatestRevisionId() + "）：" + System.lineSeparator() + diagnostics);
            }
            return rawResponse(page, etagOf(page, "failed"), mediaType,
                    "/* TypeScript 编译失败（" + page.getTitle() + "，版本 " + page.getLatestRevisionId() + "）："
                            + System.lineSeparator() + diagnostics + System.lineSeparator()
                            + "请修正后再保存；源码：" + page.getTitle() + "?action=raw */");
        }
        // 普通源码（wikitext / .js / .css / 类型声明）：同样带 ETag，改动立即生效
        return rawResponse(page, etagOf(page, "source"), mediaType, content);
    }

    /** 版本化 ETag：页面每保存一版就变（{@code W/"版本-变体"}）。 */
    private static String etagOf(WikiPage page, String variant) {
        Long revision = page.getLatestRevisionId() == null ? 0L : page.getLatestRevisionId();
        return "W/\"" + revision + "-" + variant.replace('"', '_') + "\"";
    }

    /**
     * 组装 raw 响应：带 ETag 与 {@code Cache-Control: no-cache}（每次校验、改动立即生效），
     * 并保留 {@code X-Source-Revision} 便于审计与抓包定位。
     */
    private static ResponseEntity<String> rawResponse(WikiPage page, String etag,
                                                     MediaType mediaType, String body) {
        return ResponseEntity.ok()
                .contentType(mediaType)
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .header("X-Source-Revision", String.valueOf(page.getLatestRevisionId()))
                .body(body);
    }

    /** {@code If-None-Match} 是否命中给定 ETag（支持 {@code *}、弱校验前缀与多值）。 */
    private static boolean notModified(HttpServletRequest request, String etag) {
        String header = request.getHeader("If-None-Match");
        if (header == null || header.isBlank()) {
            return false;
        }
        String trimmed = header.trim();
        if ("*".equals(trimmed)) {
            return true;
        }
        String strong = etag.startsWith("W/") ? etag.substring(2) : etag;
        for (String candidate : trimmed.split(",")) {
            String value = candidate.trim();
            if (value.equals(etag) || value.equals(strong) || value.equals("W/" + strong)) {
                return true;
            }
        }
        return false;
    }

    /** 是否是 JavaScript 类型（{@code text/javascript}）。 */
    private static boolean isScriptMediaType(MediaType mediaType) {
        return mediaType != null && "javascript".equalsIgnoreCase(mediaType.getSubtype());
    }

    private static MediaType rawMediaType(String ctype) {
        String value = Strings.trimToEmpty(ctype).toLowerCase(Locale.ROOT);
        String type = switch (value) {
            case "text/javascript", "application/javascript", "text/ecmascript" -> "text/javascript";
            case "text/css" -> "text/css";
            default -> "text/plain";
        };
        return MediaType.parseMediaType(type + ";charset=UTF-8");
    }

    /** 保存编辑（页面不存在时创建）；权限来自可配置的访问规则（编辑 / 创建），受保护页面只有管理员能保存。 */
    @PostMapping({"/wiki/{title}", "/wiki/{title}/{*rest}"})
    public Object submitEdit(@PathVariable("title") String title,
                             @PathVariable(value = "rest", required = false) String rest,
                             @RequestParam("content") String content,
                             @RequestParam(value = "comment", required = false) String comment,
                              @RequestParam(value = "section", required = false) Integer section,
                              @RequestParam(value = "baserevid", required = false) Long baserevid,
                              @RequestParam(value = "botEdit", required = false) String botEdit,
                              @RequestParam(value = "minor", required = false) String minor,
                              @RequestParam(value = "watch", required = false) String watch,
                             HttpServletRequest request) {
        String normalized = WikiTitles.normalize(fullTitle(title, rest));
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message("error.titleRequired"));
        }
        WikiTitles.validate(fullTitle(title, rest)).ifPresent(error -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, titleErrorMessage(error));
        });
        WikiUser user = UserSession.current(request);
        Optional<WikiBlock> block = this.blockService.currentBlock(user);
        if (block.isPresent()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, this.blockService.notice(block.get()));
        }
        WikiPage existing = this.wikiPageService.getByTitle(normalized).orElse(null);
        // 「机器人」组用户可把本次编辑标记为机器人编辑（默认不在最近更改中显示）
        boolean botEditFlag = "1".equals(botEdit) && user != null
                && this.permissionService.has(user.getId(), PermissionService.GROUP_BOT);
        boolean minorEditFlag = "1".equals(minor);
        boolean watchAfterSave = "1".equals(watch);
        AccessAction action = existing == null ? AccessAction.CREATE : AccessAction.EDIT;
        if (!this.accessControlService.can(action, normalized, user)) {
            String key = user == null ? "error.loginRequired"
                    : existing == null ? "error.createForbidden" : "error.editForbidden";
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message(key));
        }
        if (!canModifyUserPage(normalized, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message("error.userPageForbidden"));
        }
        if (existing != null && Boolean.TRUE.equals(existing.getProtectedPage())
                && !isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message("error.protected"));
        }
        // 禁用标题名单 / 编辑邮箱白名单（WikiKernel:Titleblacklist / WikiKernel:Email-whitelist）
        String saveRestriction = saveRestriction(normalized, existing, user);
        if (saveRestriction != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, saveRestriction);
        }

        // 整页编辑：基于的版本已被他人推进时进入「编辑冲突提示」页（不自动合并、不直接覆盖）。
        if (section == null && existing != null) {
            WikiRevision latestRevision = this.wikiPageService.getLatestRevision(existing.getId()).orElse(null);
            if (latestRevision != null && baserevid != null
                    && !Objects.equals(baserevid, latestRevision.getId())) {
                return conflictNoticeView(normalized, existing, user, content, null, comment);
            }
        }

        // 段落编辑（仿 MediaWiki）：只把该段替换进最新版本，其余内容（含别人的改动）逐字保留。
        // 先算出「实际会写入的整页内容」：保存前钩子（审核排队）与保存都用它，否则通过审核后会丢掉其余段落。
        boolean sectionEdit = existing != null && section != null && codeLanguage(normalized) == null;
        String saveContent = content;
        if (sectionEdit) {
            saveContent = mergeSection(existing, section, content, baserevid);
            if (saveContent == null) {
                return conflictNoticeView(normalized, existing, user, content, section, comment);
            }
        }

        // 保存前钩子（EditGuard，如 ConfirmEdit 的验证码、插件版滥用过滤器 / 审核）：按 order 升序执行；
        // BLOCK = 回到编辑表单（已填内容与摘要保留，message 显示在提示区）；WARN = 保存后提示；HANDLED = 钩子已自行处理
        RequestEditContext editContext = RequestEditContext.web(
                request, viewerOf(user), new RequestEditContext.FormValues(normalized, existing == null,
                        saveContent, comment, minorEditFlag, action.id(), baserevid,
                        RequestEditContext.formParameters(request), false));
        EditHookService.Decision editDecision = this.editHookService.beforeSave(editContext);
        if (editDecision.blocked()) {
            return renderEditHookFailure(normalized, existing, user, content, section, comment,
                    editDecision.message(), request);
        }
        if (editDecision.handled()) {
            return "redirect:" + (editDecision.redirect().isEmpty()
                    ? WikiUrls.pagePath(normalized) : editDecision.redirect());
        }
        // 钩子登记的变更标签（如滥用过滤器的 tag 操作）随保存写入版本
        String changeTags = changeTags(editContext);

        if (sectionEdit) {
            WikiRevision latestRevision = this.wikiPageService.getLatestRevision(existing.getId()).orElse(null);
            WikiPage saved = this.wikiPageService.editPageIfLatest(user, normalized, saveContent, comment,
                    botEditFlag, minorEditFlag, changeTags,
                    latestRevision == null ? existing.getLatestRevisionId() : latestRevision.getId());
            if (saved == null) {
                WikiPage latestPage = this.wikiPageService.getByTitle(normalized).orElse(existing);
                return conflictNoticeView(normalized, latestPage, user, content, section, comment);
            }
            updateWatchAfterSave(user, normalized, watchAfterSave);
            return "redirect:" + rememberEditWarnings(request, editDecision.warnings(),
                    WikiUrls.pagePath(normalized) + sectionFragment(saveContent, section));
        }

        if (existing != null && section == null) {
            // 保存瞬间又被别人改写（竞态）时 CAS 会失败：同样进入冲突提示页
            Long expected = this.wikiPageService.getLatestRevision(existing.getId())
                    .map(WikiRevision::getId).orElse(existing.getLatestRevisionId());
            WikiPage saved = this.wikiPageService.editPageIfLatest(user, normalized, saveContent, comment,
                    botEditFlag, minorEditFlag, changeTags, expected);
            if (saved == null) {
                WikiPage latestPage = this.wikiPageService.getByTitle(normalized).orElse(existing);
                return conflictNoticeView(normalized, latestPage, user, content, null, comment);
            }
        } else {
            this.wikiPageService.editPage(user, normalized, saveContent, comment, botEditFlag,
                    minorEditFlag, changeTags);
        }
        updateWatchAfterSave(user, normalized, watchAfterSave);
        return "redirect:" + rememberEditWarnings(request, editDecision.warnings(),
                WikiUrls.pagePath(normalized));
    }

    /**
     * 段落编辑：把提交的段落文本替换进**最新版本**（其余段落逐字保留），返回实际会写入的整页内容。
     * 段落不存在 / 基准版本已过期且该段被别人改过 / 替换失败时返回 {@code null}（调用方进入编辑冲突提示页）。
     */
    private String mergeSection(WikiPage existing, Integer section, String content, Long baserevid) {
        WikiRevision latestRevision = this.wikiPageService.getLatestRevision(existing.getId()).orElse(null);
        String latest = latestRevision == null
                ? this.wikiPageService.getLatestContent(existing.getId()).orElse("")
                : latestRevision.getContent();
        String latestSection = WikiSections.extract(latest, section);
        if (latestSection == null) {
            return null;
        }
        if (baserevid != null && (latestRevision == null
                || !Objects.equals(baserevid, latestRevision.getId()))) {
            String baseSection = this.wikiPageService.getRevision(baserevid)
                    .filter(revision -> Objects.equals(revision.getPageId(), existing.getId()))
                    .filter(revision -> !Boolean.TRUE.equals(revision.getDeleted()))
                    .map(WikiRevision::getContent)
                    .map(text -> WikiSections.extract(text, section))
                    .orElse(null);
            if (baseSection == null || !baseSection.equals(latestSection)) {
                // 该段在你编辑期间被别人改过：进入冲突提示页（你的文本保留在编辑框中）
                return null;
            }
        }
        return WikiSections.replace(latest, section, content);
    }

    /** 会话属性：保存前钩子的 {@code WARN} 提示（保存成功后显示一次，随后清除）。 */
    static final String SESSION_EDIT_WARNINGS = "wikikernel.editHook.warnings";

    /** 把保存前钩子的提示存进会话，返回跳转目标（没有提示时原样返回）。 */
    private String rememberEditWarnings(HttpServletRequest request, List<String> warnings, String target) {
        if (warnings == null || warnings.isEmpty()) {
            return target;
        }
        request.getSession(true).setAttribute(SESSION_EDIT_WARNINGS, List.copyOf(warnings));
        return target;
    }

    /** 取出并清除本次保存留下的钩子提示（读取时不创建会话）。 */
    private static List<String> takeEditWarnings(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            return List.of();
        }
        Object value = session.getAttribute(SESSION_EDIT_WARNINGS);
        session.removeAttribute(SESSION_EDIT_WARNINGS);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) {
                warnings.add(text);
            }
        }
        return List.copyOf(warnings);
    }

    // ==================== 各类页面 ====================

    private ModelAndView renderView(String title, Map<String, String> parameters, HttpServletRequest request) {
        WikiUser currentUser = UserSession.current(request);
        requireRead(title, currentUser);
        if (WikiLinkScanner.isCategoryTitle(title)) {
            // 分类页：说明（可选）+ 成员列表（MediaWiki 的分类命名空间）
            return renderCategory(title, currentUser);
        }
        if (FileTitles.isFileTitle(title)) {
            // 文件页：文件存在时渲染「文件:X」页面（预览 + 元信息 + 说明 + 文件历史）
            Optional<WikiFile> fileRow = this.fileService.findAnyByName(FileTitles.fileNameOf(title));
            if (fileRow.isPresent()) {
                return renderFilePage(FileTitles.titleOf(fileRow.get().getName()), fileRow.get(), currentUser);
            }
        }
        WikiPage page = this.wikiPageService.getVisibleByTitle(title).orElse(null);
        if (page == null) {
            // 已删除的页面：给「此页面已被删除」提示（管理员可找回最近的源代码并恢复；仿 MediaWiki）
            Optional<WikiPage> deletedPage = this.wikiPageService.findDeletedByTitle(title);
            if (deletedPage.isPresent()) {
                return renderDeletedPage(deletedPage.get(), currentUser);
            }
            if (this.wikiPageService.getByTitle(title).isPresent()) {
                // 页面存在但被隐藏：仍然 404，不暴露存在性
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, message("error.pageMissing", title));
            }
            // 已注册用户的用户页：显示资料卡 + 「尚未创建」提示（用户交互关闭时资料卡整体隐藏 → 走 404）；
            // 其余不存在的页面统一 404（自定义 404 页，创建入口是 404 页里的「创建本页面」蓝链，不再自动跳编辑器）
            String userName = userNamespaceName(title);
            if (this.userProfileHooks.userProfileEnabled()
                    && userName != null && !userName.isEmpty()
                    && this.userService.findByUsername(userName).isPresent()) {
                return renderMissingUserPage(title, currentUser);
            }
            // 插件认领「不存在的标题」的显示（PageDisplayHook.renderMissingPage，如审核的「待审创建」页）：
            // 作者 / 审核员看到待审内容预览，其他人仍然 404（不暴露内容）
            ModelAndView claimed = pluginMissingPage(title, parameters, currentUser, request);
            if (claimed != null) {
                return claimed;
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message("error.pageMissing", title));
        }

        // 差异对比页（仿 MediaWiki：?diff=prev&oldid=N / ?diff=<新>&oldid=<旧> / ?diff=cur&oldid=N）
        if (parameters.containsKey("diff")) {
            return renderDiff(page, parameters, currentUser);
        }

        WikiRevision oldRevision = null;
        String content;
        Long oldId = parseId(parameters.get("oldid"));
        if (oldId == null && !"no".equals(parameters.get("redirect"))) {
            // #REDIRECT [[目标]]：自动跳转（?redirect=no 可查看重定向页本身）；带 redirectfrom 便于目标页提示来源
            Optional<String> redirectTarget = resolveRedirectTarget(title);
            if (redirectTarget.isPresent()) {
                return new ModelAndView("redirect:" + WikiUrls.pagePath(redirectTarget.get())
                        + "?redirectfrom=" + encodeQueryValue(title));
            }
        }
        if (oldId != null) {
            oldRevision = this.wikiPageService.getRevision(oldId)
                    .filter(revision -> Objects.equals(revision.getPageId(), page.getId()))
                    .filter(revision -> !Boolean.TRUE.equals(revision.getDeleted()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            message("error.revisionMissing", oldId)));
            if (Objects.equals(oldRevision.getId(), page.getLatestRevisionId())) {
                // 固定链接（?oldid=当前版本）按最新版展示：打开永久链接不提示“旧版本”（仿 MediaWiki）
                oldRevision = null;
                content = this.wikiPageService.getLatestContent(page.getId()).orElse("");
            } else {
                content = oldRevision.getContent();
            }
        } else {
            content = this.wikiPageService.getLatestContent(page.getId()).orElse("");
        }

        String codeLanguage = codeLanguage(page.getTitle());
        String html = "";
        String displayTitle = null;
        List<WikiLinkScanner.CategoryRef> categories = List.of();
        // 跨语言链接（[[en:页面]]，已从正文剥离）：显示在侧栏「语言」区块
        List<InterwikiService.Link> languageLinks = List.of();
        // 渲染时的页面属性（插件的解析器函数 / 魔术字 / 行为开关写入；非 wikitext 页面为空）
        Map<String, String> pageProperties = Map.of();
        // 插件页面显示钩子（PageDisplayHook）：先让插件认领整页正文（图表: 整页图、微件: 代码页这类非 wikitext 页面），
        // 渲染结束后再询问标题上方 / 正文下方的 HTML 片段（页面横幅、评论区等）
        WikiPageDisplayContext displayContext = new WikiPageDisplayContext(page.getId(), page.getTitle(),
                content, oldRevision != null, parameters, viewerOf(currentUser), displaySiteName(),
                baseUrlOf(request));
        boolean bodyClaimed = false;
        String claimedBody = this.pageDisplayHookService.renderBody(displayContext);
        if (claimedBody != null) {
            html = claimedBody;
            bodyClaimed = true;
        }
        if (!bodyClaimed && codeLanguage == null) {
            // 正文渲染走页面缓存（读缓存 → 命中直接用 → 未命中渲染后写缓存）：
            // 只有「查看最新版本」用缓存，?oldid= 看旧版本时每次实时渲染；缓存条目按版本号校验，
            // 页面一改就是新版本，不会拿到旧正文。段落编辑链接与用户有关，命中后另外注入。
            PageBody body = oldRevision == null
                    ? this.pageCacheService.body(page, content)
                    : this.pageBodyRenderer.render(page.getTitle(), content,
                    oldRevision.getId() == null ? 0L : oldRevision.getId());
            html = body.html();
            displayTitle = body.displayTitle();
            categories = body.categories();
            languageLinks = body.interwikiLinks();
            pageProperties = body.properties();
            if (oldRevision == null && canEditPage(page.getTitle(), page, currentUser)) {
                // 段落编辑链接（仿 MediaWiki）：只有可以编辑的人看得到
                html = SectionEditLinks.inject(html, page.getTitle(), content,
                        message("section.edit"), message("section.editTitle"));
            }
        }

        ModelAndView modelAndView = new ModelAndView("wiki/view");
        modelAndView.addObject("page", page);
        modelAndView.addObject("wikiTitle", page.getTitle());
        modelAndView.addObject("displayTitle", displayTitle);
        modelAndView.addObject("pageUrl", WikiUrls.pagePath(page.getTitle()));
        modelAndView.addObject("activeTab", "view");
        modelAndView.addObject("oldRevision", oldRevision);
        // 旧版本提示里的编辑者：把用户名渲染成指向用户页的蓝链（找不到用户名时退回「用户 #编号」样式）
        modelAndView.addObject("oldRevisionUserName",
                oldRevision == null || oldRevision.getUserId() == null
                        ? null
                        : this.userService.usernames(List.of(oldRevision.getUserId()))
                        .get(oldRevision.getUserId()));
        // 页脚「此页面最后编辑于…」：旧版本查看用该版本时间，否则用页面的最后编辑时间
        modelAndView.addObject("footerLastEdited",
                oldRevision != null ? oldRevision.getCreateAt() : page.getUpdateAt());
        modelAndView.addObject("redirectFrom", redirectFrom(title, parameters));
        // 保存前钩子（EditGuard）的 WARN 提示：保存成功后显示一次（读取即清除）
        List<String> editGuardWarnings = takeEditWarnings(request);
        if (!editGuardWarnings.isEmpty()) {
            modelAndView.addObject("editGuardWarnings", editGuardWarnings);
        }
        modelAndView.addObject("codeLanguage", codeLanguage);
        modelAndView.addObject("codeText", codeLanguage == null ? null : content);
        // TypeScript 编译状态（.ts / .tsx）：显示编译成功 / 语法诊断（「保存时编译」开启时才有内容）
        addTypeScriptNotice(modelAndView, page, content);
        modelAndView.addObject("widgetPage", WikiNamespaces.isWidget(page.getTitle()));
        modelAndView.addObject("contentHtml", html);
        modelAndView.addObject("pageCategories", categoryRows(categories));
        modelAndView.addObject("pageCss", sanitizeCss(
                this.wikiPageService.getCSS(title, activeSkinId(request))));
        // AI 代理协助的版本：词条底部渲染一枚隐藏水印（不可见、不干扰阅读；见 wiki/view.html）
        WikiRevision displayed = oldRevision != null ? oldRevision
                : this.wikiPageService.getLatestRevision(page.getId()).orElse(null);
        if (displayed != null && Boolean.TRUE.equals(displayed.getAiAssisted())) {
            modelAndView.addObject("aiAssisted", true);
            modelAndView.addObject("aiModel", displayed.getAiModel());
            modelAndView.addObject("aiRevisionId", displayed.getId());
        }
        // 评论区（<comment />）已迁到插件 comments：由 PageDisplayHook.afterContent 注入
        // 用户页资料卡（头像 + 额外链接）由 web/UserProfileAdvice 收集插件 UserProfileHook 的结果
        addPagePermissionAttributes(modelAndView, title, page, currentUser);
        applyUserToolsSidebar(modelAndView, title, currentUser, languageLinks);
        modelAndView.addObject("languageLinks", languageLinks);
        // 插件页面级能力：隐藏标题（NoTitle）、<head> 扩展（WikiSEO）、浏览钩子（HitCounters）
        displayContext.properties(pageProperties);
        // 插件页面显示钩子：标题上方 / 正文之前（提示条）/ 正文下方 / 该页额外的 head 片段
        List<String> beforeTitleFragments = new ArrayList<>(this.pageDisplayHookService.beforeTitle(displayContext));
        // <indicator name="…">（仿 MediaWiki 页面状态指示器）：从页面属性还原，浮在标题右上角
        String indicators = indicatorsHtml(pageProperties);
        if (!indicators.isEmpty()) {
            beforeTitleFragments.add(indicators);
        }
        List<String> beforeContentFragments = this.pageDisplayHookService.beforeContent(displayContext);
        List<String> afterContentFragments = this.pageDisplayHookService.afterContent(displayContext);
        List<String> displayHeadItems = this.pageDisplayHookService.headItems(displayContext);
        if (!beforeTitleFragments.isEmpty()) {
            modelAndView.addObject("pageBeforeTitleHtml", beforeTitleFragments);
        }
        if (!beforeContentFragments.isEmpty()) {
            modelAndView.addObject("pageBeforeContentHtml", beforeContentFragments);
        }
        if (!afterContentFragments.isEmpty()) {
            modelAndView.addObject("pageAfterContentHtml", afterContentFragments);
        }
        modelAndView.addObject("hideTitle", "1".equals(pageProperties.get("notitle")));
        applyHeadExtensions(modelAndView, page.getId(), page.getTitle(), pageProperties, parameters, request,
                displayHeadItems);
        firePageView(page, currentUser, oldRevision);
        return modelAndView;
    }

    /**
     * 页面**不存在**时让插件认领显示（{@link PageDisplayHook#renderMissingPage}，如审核的「待审创建」页）：
     * 第一个返回非 {@code null} 的钩子生效，core 渲染通用外壳（标题 + 钩子给的 HTML）；
     * 没有插件认领时返回 {@code null}（调用方照常 404）。
     */
    private ModelAndView pluginMissingPage(String title, Map<String, String> parameters,
                                           WikiUser currentUser, HttpServletRequest request) {
        WikiPageDisplayContext displayContext = new WikiPageDisplayContext(0L, title, "", false,
                parameters, viewerOf(currentUser), displaySiteName(), baseUrlOf(request));
        String html = this.pageDisplayHookService.renderMissingPage(displayContext);
        if (html == null) {
            return null;
        }
        ModelAndView modelAndView = new ModelAndView("wiki/plugin-missing-page");
        modelAndView.addObject("page", null);
        modelAndView.addObject("wikiTitle", title);
        modelAndView.addObject("pageUrl", WikiUrls.pagePath(title));
        modelAndView.addObject("activeTab", "view");
        modelAndView.addObject("pluginPageHtml", html);
        addPagePermissionAttributes(modelAndView, title, null, currentUser);
        applyUserToolsSidebar(modelAndView, title, currentUser);
        return modelAndView;
    }

    /**
     * 文件页的媒体预览：询问插件的 {@link FileEmbedRenderer}
     * （多媒体预览）是否渲染播放器；没有插件处理时返回 {@code null}（模板显示图片 / 图标占位）。
     */
    private String pluginMediaEmbed(WikiFile file) {
        List<FileEmbedRenderer> renderers =
                this.pluginManager.extensions(FileEmbedRenderer.class).stream()
                        .sorted(Comparator.comparingInt(
                                FileEmbedRenderer::order))
                        .toList();
        if (renderers.isEmpty()) {
            return null;
        }
        FileEmbedCall call =
                WikiFileEmbedCall.of(file, this.fileService, null, "", "", Map.of());
        for (FileEmbedRenderer renderer : renderers) {
            try {
                String html = renderer.render(call);
                if (html != null && !html.isBlank()) {
                    return html;
                }
            } catch (RuntimeException ignored) {
                // 插件异常不影响文件页渲染
            }
        }
        return null;
    }

    /** 当前用户视图（供插件钩子判断用户组；匿名返回 {@link Viewer#ANONYMOUS}）。 */
    private Viewer viewerOf(WikiUser user) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            return Viewer.ANONYMOUS;
        }
        Set<String> groups = this.permissionService.namesOf(user.getId());
        return new Viewer(user.getId(), user.getUsername(), false, groups,
                groups.contains(PermissionService.GROUP_BOT));
    }

    /** 站点显示名（仿 MediaWiki 的 $wgSitename）。 */
    private String displaySiteName() {
        return SiteNameService.normalize(
                this.siteSettingsService.get(SiteNameService.KEY, SiteNameService.DEFAULT_NAME));
    }

    /** 请求的站点根地址（如 {@code http://localhost:18080}，不带尾斜杠）。 */
    private static String baseUrlOf(HttpServletRequest request) {
        StringBuilder base = new StringBuilder();
        base.append(request.isSecure() ? "https" : "http").append("://").append(request.getServerName());
        int port = request.getServerPort();
        boolean standard = request.isSecure() ? port == 443 : port == 80;
        if (!standard) {
            base.append(':').append(port);
        }
        return base.toString();
    }

    /**
     * {@code <indicator name="…">…</indicator>}（仿 MediaWiki 页面状态指示器）：
     * 从页面属性 {@code indicator-<名字>} 还原成标题右上角的小图标
     * （按名字排序；同名只显示一个，渲染时最后写入的生效）。
     */
    private static String indicatorsHtml(Map<String, String> pageProperties) {
        if (pageProperties == null || pageProperties.isEmpty()) {
            return "";
        }
        List<String> keys = pageProperties.keySet().stream()
                .filter(key -> key.startsWith("indicator-"))
                .sorted()
                .toList();
        if (keys.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<div class=\"mw-indicators\">");
        for (String key : keys) {
            String value = pageProperties.get(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            html.append("<div class=\"mw-indicator\" data-indicator=\"")
                    .append(key.substring("indicator-".length())
                            .replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;"))
                    .append("\">").append(value).append("</div>");
        }
        html.append("</div>");
        return html.toString();
    }

    /**
     * 调用**页面级** {@link HtmlHeadProvider}（{@code siteWide()=false}）：把插件生成的
     * meta / link 片段与 <title> 覆盖放进模型（SEO、TemplateData 等要用本次渲染的页面属性）。
     * <p>
     * 站点级实现（{@code siteWide()=true}）由全局 advice {@link PluginHeadAdvice} 在所有页面统一注入：
     * 这里只取页面级实现，避免同一片段注入两遍——脚本会执行两次（图片查看器弹两层等问题）。
     */
    private void applyHeadExtensions(ModelAndView modelAndView, long pageId, String pageTitle,
                                     Map<String, String> pageProperties,
                                     Map<String, String> parameters, HttpServletRequest request,
                                     List<String> extraItems) {
        // 按客户端依赖（plugin.clientRequires）拓扑顺序取实现：被依赖插件的片段在前；
        // 客户端依赖缺失时先留一条 HTML 注释警告（页面照常渲染）
        List<HtmlHeadProvider> providers = this.pluginManager.headProviders(false).stream()
                .filter(provider -> !provider.siteWide())
                .toList();
        List<String> items = new ArrayList<>(this.pluginManager.clientDependencyWarnings());
        if (extraItems != null) {
            items.addAll(extraItems);
        }
        String htmlTitle = null;
        if (!providers.isEmpty()) {
            HeadContext context = new RequestHeadContext(pageId, pageTitle, parameters, pageProperties, request, displaySiteName(), this.fileService);
            for (HtmlHeadProvider provider : providers) {
                try {
                    List<String> provided = provider.headItems(context);
                    if (provided != null) {
                        items.addAll(provided);
                    }
                    if (htmlTitle == null) {
                        String title = provider.htmlTitle(context);
                        if (title != null && !title.isBlank()) {
                            htmlTitle = title;
                        }
                    }
                } catch (RuntimeException ignored) {
                    // 插件异常不影响页面渲染
                }
            }
        }
        if (!items.isEmpty()) {
            modelAndView.addObject("headItems", items);
        }
        if (htmlTitle != null) {
            modelAndView.addObject("htmlTitle", htmlTitle);
        }
    }

    /** 触发 {@link PageViewHook}（词条阅读视图；旧版本 / diff 不算一次浏览）。 */
    private void firePageView(WikiPage page, WikiUser currentUser, WikiRevision oldRevision) {
        if (oldRevision != null) {
            return;
        }
        List<PageViewHook> hooks = this.pluginManager.extensions(PageViewHook.class);
        if (hooks.isEmpty()) {
            return;
        }
        Set<String> groups = currentUser == null || currentUser.getId() == null
                ? Set.of()
                : this.permissionService.namesOf(currentUser.getId());
        PageViewEvent event = new PageViewEvent(page.getId(), page.getTitle(), currentUser,
                groups.contains(PermissionService.GROUP_BOT), groups);
        for (PageViewHook hook : hooks) {
            try {
                hook.pageViewed(event);
            } catch (RuntimeException ignored) {
                // 插件异常不影响页面渲染
            }
        }
    }


    /** 文件名 → 媒体文件（不区分大小写；支持「文件:X」前缀；找不到返回 null）。 */
    private WikiFile findFile(String fileName) {
        if (fileName == null || fileName.isBlank() || fileService == null) {
            return null;
        }
        String name = fileName.trim();
        if (FileTitles.isFileTitle(name)) {
            name = FileTitles.fileNameOf(name);
        }
        return fileService.findAnyByName(name).orElse(null);
    }

    /**
     * 分类页（{@code 分类:X}）：可选说明页（{@code 分类:X} 本身的正文）+ 成员列表（含子分类）。
     * 成员来自实时扫描（{@code PageLinkService.categoryMembers}），无需索引表。
     */
    private ModelAndView renderCategory(String title, WikiUser user) {
        String canonical = WikiLinkScanner.canonicalCategoryTitle(title);
        WikiPage description = this.wikiPageService.getVisibleByTitle(canonical).orElse(null);

        String html = "";
        List<Map<String, Object>> descriptionCategories = List.of();
        if (description != null) {
            String content = this.wikiPageService.getLatestContent(description.getId()).orElse("");
            // 说明页正文同样走页面缓存（成员列表不缓存：每次实时扫描，分类一改就能看到）
            PageBody body = this.pageCacheService.body(description, content);
            html = body.html();
            descriptionCategories = categoryRows(body.categories());
            if (canEditPage(canonical, description, user)) {
                html = SectionEditLinks.inject(html, canonical, content,
                        message("section.edit"), message("section.editTitle"));
            }
        }

        List<Map<String, Object>> members = new ArrayList<>();
        List<Map<String, Object>> subcategories = new ArrayList<>();
        for (PageCategoryService.Member member : this.pageCategoryService.members(canonical, CATEGORY_MEMBER_LIMIT)) {
            WikiPage page = member.page();
            boolean subcategory = WikiLinkScanner.isCategoryTitle(page.getTitle());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", page.getTitle());
            row.put("label", subcategory
                    ? WikiLinkScanner.categoryName(page.getTitle())
                    : page.getTitle());
            row.put("url", WikiUrls.pagePath(page.getTitle()));
            row.put("sortKey", member.sortKey());
            (subcategory ? subcategories : members).add(row);
        }

        ModelAndView modelAndView = new ModelAndView("wiki/category");
        modelAndView.addObject("page", description);
        if (description != null) {
            modelAndView.addObject("footerLastEdited", description.getUpdateAt());
        }
        modelAndView.addObject("wikiTitle", title);
        modelAndView.addObject("pageUrl", WikiUrls.pagePath(title));
        modelAndView.addObject("activeTab", "view");
        modelAndView.addObject("categoryTitle", canonical);
        modelAndView.addObject("categoryName", WikiLinkScanner.categoryName(canonical));
        modelAndView.addObject("descriptionHtml", html);
        modelAndView.addObject("members", members);
        modelAndView.addObject("subcategories", subcategories);
        modelAndView.addObject("pageCategories", descriptionCategories);
        addPagePermissionAttributes(modelAndView, title, description, user);
        applyUserToolsSidebar(modelAndView, canonical, user);
        return modelAndView;
    }

    /**
     * 文件页（{@code 文件:X}）：文件预览 + 元信息 + 说明页正文 + 文件历史（版本回滚 / 上传新版本 / 删除）。
     * 说明页就是 {@code 文件:X} 这个普通 Wiki 页面（可编辑、看历史、参与分类 / 讨论）。
     */
    private ModelAndView renderFilePage(String title, WikiFile file, WikiUser user) {
        boolean deleted = Boolean.TRUE.equals(file.getDeleted());
        boolean canDelete = this.accessControlService.can(AccessAction.DELETE, "文件:" + file.getName(), user);
        boolean canUpload = this.accessControlService.can(AccessAction.UPLOAD, "文件:" + file.getName(), user);

        WikiPage descriptionPage = this.wikiPageService.getVisibleByTitle(title).orElse(null);
        String html = "";
        List<WikiLinkScanner.CategoryRef> categories = List.of();
        if (descriptionPage != null) {
            String content = this.wikiPageService.getLatestContent(descriptionPage.getId()).orElse("");
            // 文件说明页正文同样走页面缓存（渲染钩子由 PageBodyRenderer 统一执行）
            PageBody body = this.pageCacheService.body(descriptionPage, content);
            html = body.html();
            categories = body.categories();
            if (canEditPage(title, descriptionPage, user)) {
                html = SectionEditLinks.inject(html, title, content,
                        message("section.edit"), message("section.editTitle"));
            }
        }

        List<WikiFileVersion> versions = this.fileService.versions(file);
        Set<Long> userIds = new LinkedHashSet<>(this.fileViewMapper.uploaderIds(List.of(file)));
        userIds.addAll(this.fileViewMapper.versionUploaderIds(versions));
        Map<Long, String> userNames = this.userService.usernames(userIds);

        ModelAndView modelAndView = new ModelAndView("wiki/file");
        modelAndView.addObject("title", title);
        modelAndView.addObject("wikiTitle", title);
        modelAndView.addObject("pageUrl", WikiUrls.pagePath(title));
        modelAndView.addObject("activeTab", "view");
        modelAndView.addObject("page", descriptionPage);
        if (descriptionPage != null) {
            modelAndView.addObject("footerLastEdited", descriptionPage.getUpdateAt());
        }
        // 多媒体预览（仿 TimedMediaHandler）：插件（FileEmbedRenderer）决定音视频文件页的播放器 HTML
        String mediaKind = this.fileService.mediaKind(file.getName());
        modelAndView.addObject("mediaKind", mediaKind);
        modelAndView.addObject("mediaPlayerHtml", pluginMediaEmbed(file));
        modelAndView.addObject("file", this.fileViewMapper.row(file, userNames));
        modelAndView.addObject("versions", versions.stream()
                .map(version -> this.fileViewMapper.versionRow(version, userNames))
                .toList());
        modelAndView.addObject("deleted", deleted);
        modelAndView.addObject("deletedByName",
                file.getDeletedBy() == null ? null : userNames.get(file.getDeletedBy()));
        modelAndView.addObject("deletedAt", file.getDeletedAt());
        modelAndView.addObject("canDelete", canDelete);
        modelAndView.addObject("canUpload", canUpload);
        // 文件用途（引用追溯，仿 MediaWiki）：哪些页面嵌入了本文件（按查看权限过滤）
        modelAndView.addObject("fileUsage", this.accessControlService
                .filterReadable(this.pageLinkService.pagesUsingFile(file.getName(), 200),
                        WikiPage::getTitle, user)
                .stream()
                .map(usage -> {
                    Map<String, Object> row = new LinkedHashMap();
                    row.put("title", usage.getTitle());
                    row.put("url", WikiUrls.pagePath(usage.getTitle()));
                    return row;
                })
                .toList());
        modelAndView.addObject("contentHtml", html);
        modelAndView.addObject("pageCategories", categoryRows(categories));
        addPagePermissionAttributes(modelAndView, title, descriptionPage, user);
        applyUserToolsSidebar(modelAndView, title, user);
        return modelAndView;
    }

    /** 分类栏模型：规范标题 + 显示名 + URL + 是否红链（分类说明页不存在）。 */
    private List<Map<String, Object>> categoryRows(List<WikiLinkScanner.CategoryRef> refs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (WikiLinkScanner.CategoryRef ref : refs) {
            if (!seen.add(ref.title())) {
                continue;
            }
            boolean red = this.wikiPageService.getVisibleByTitle(ref.title()).isEmpty();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", ref.title());
            row.put("label", WikiLinkScanner.categoryName(ref.title()));
            row.put("url", WikiUrls.pagePath(ref.title()));
            row.put("red", red);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 解析 {@code #REDIRECT} 链：最多 5 跳，遇到循环 / 链过长返回空（按普通页面渲染，避免死循环）；
     * 目标不存在时也返回目标标题（浏览器会落到创建页，仿 MediaWiki 红链目标）。
     */
    private Optional<String> resolveRedirectTarget(String title) {
        String current = title;
        Set<String> seen = new LinkedHashSet<>();
        seen.add(title);
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            WikiPage page = this.wikiPageService.getVisibleByTitle(current).orElse(null);
            if (page == null) {
                return current.equals(title) ? Optional.empty() : Optional.of(current);
            }
            Optional<String> next = WikiRedirect.targetOf(
                    this.wikiPageService.getLatestContent(page.getId()).orElse(""));
            if (next.isEmpty()) {
                return current.equals(title) ? Optional.empty() : Optional.of(current);
            }
            if (!seen.add(next.get())) {
                return Optional.empty(); // 循环重定向：渲染原页面
            }
            current = next.get();
        }
        return Optional.empty(); // 链过长：渲染原页面
    }

    /** 目标页上的「重定向自 X」提示（仅当 URL 带 redirectfrom 且与自身不同）。 */
    private static String redirectFrom(String title, Map<String, String> parameters) {
        String from = Strings.trimToEmpty(parameters.get("redirectfrom"));
        return from.isEmpty() || from.equals(title) ? null : from;
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private ModelAndView renderEdit(String title, Map<String, String> parameters, HttpServletRequest request) {
        return renderEdit(title, parameters, request, false);
    }

    /** {@code visual=true}：{@code ?veaction=edit} 打开的可视化编辑模式（页面加载即打开可视化面板）。 */
    private ModelAndView renderEdit(String title, Map<String, String> parameters,
                                    HttpServletRequest request, boolean visual) {
        WikiUser user = UserSession.current(request);
        WikiPage page = this.wikiPageService.getByTitle(title).orElse(null);
        if (page != null) {
            requireRead(title, user);
        }
        Optional<WikiBlock> block = this.blockService.currentBlock(user);
        if (block.isPresent()) {
            // 被封禁：显示封禁说明（仍可阅读；仿 MediaWiki 的「您已被封禁」页）
            return renderBlocked(title, page, user, block.get());
        }
        if (!canEditPage(title, page, user)) {
            // 没有编辑 / 创建权限（含未登录、受保护页面、他人用户页）：显示“查看源代码”
            return renderSource(title, page, user);
        }
        String saveRestriction = saveRestriction(title, page, user);
        if (saveRestriction != null) {
            // 标题黑名单 / 编辑邮箱白名单不允许保存：同样只给“查看源代码”并说明原因
            return renderSource(title, page, user, saveRestriction);
        }
        WikiRevision editingOldRevision = null;
        String content = "";
        Long loadedRevisionId = null;
        if (page != null) {
            Long oldId = parseId(parameters.get("oldid"));
            if (oldId != null) {
                editingOldRevision = this.wikiPageService.getRevision(oldId)
                        .filter(revision -> Objects.equals(revision.getPageId(), page.getId()))
                        .filter(revision -> !Boolean.TRUE.equals(revision.getDeleted()))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                message("error.revisionMissing", oldId)));
                content = editingOldRevision.getContent();
                loadedRevisionId = editingOldRevision.getId();
            } else {
                content = this.wikiPageService.getLatestContent(page.getId()).orElse("");
                loadedRevisionId = page.getLatestRevisionId();
            }
        }

        // 段落编辑（仿 MediaWiki）：编辑框里只有该段（标题行 + 正文），保存时合并回整页
        Integer editSection = page == null || codeLanguage(title) != null
                ? null : parseSection(parameters.get("section"));
        String editSectionHeading = null;
        String editSummary = "";
        if (editSection != null) {
            String sectionText = WikiSections.extract(content, editSection);
            if (sectionText == null) {
                // 段落不存在（页面结构已变）：退回整页编辑
                return new ModelAndView("redirect:" + WikiUrls.pagePath(title) + "?action=edit");
            }
            editSectionHeading = WikiSections.displayHeading(WikiSections.headingOf(content, editSection));
            if (editSectionHeading == null) {
                editSectionHeading = message("edit.leadSection");
            } else {
                editSummary = "/* " + editSectionHeading + " */ ";
            }
            content = sectionText;
        }

        ModelAndView modelAndView = new ModelAndView("wiki/edit");
        modelAndView.addObject("page", page);
        modelAndView.addObject("wikiTitle", title);
        modelAndView.addObject("pageUrl", WikiUrls.pagePath(title));
        modelAndView.addObject("activeTab", "edit");
        modelAndView.addObject("isNewPage", page == null);
        modelAndView.addObject("editingOldRevision", editingOldRevision);
        modelAndView.addObject("editHeading", message(page == null ? "edit.createHeading" : "edit.heading", title));
        modelAndView.addObject("codeLanguage", codeLanguage(title));
        modelAndView.addObject("showEditToolbar", codeLanguage(title) == null);
        modelAndView.addObject("widgetPage", WikiNamespaces.isWidget(title));
        modelAndView.addObject("editContent", content);
        modelAndView.addObject("editSection", editSection);
        modelAndView.addObject("editSectionHeading", editSectionHeading);
        modelAndView.addObject("editSummary", editSummary);
        modelAndView.addObject("canBotEdit", user != null
                && this.permissionService.has(user.getId(), PermissionService.GROUP_BOT));
        modelAndView.addObject("baseRevisionId", loadedRevisionId);
        // 插件编辑页字段（EditFormField）：未豁免用户的验证码题面 / 输入框、审核开关提示等
        applyEditFormExtensions(modelAndView, title, page == null, user, parameters, request);
        addPagePermissionAttributes(modelAndView, title, page, user);
        modelAndView.addObject("veMode", visual);
        applyUserToolsSidebar(modelAndView, title, user);
        return modelAndView;
    }

    /**
     * 插件编辑页字段（{@link cc.thonly.wikikernel.common.extension.EditFormField}）：
     * 编辑框下方的额外字段（验证码等）与保存按钮附近的提示；插件异常由 {@link EditHookService} 兜底跳过。
     */
    private void applyEditFormExtensions(ModelAndView modelAndView, String title, boolean newPage,
                                         WikiUser user, Map<String, String> parameters,
                                         HttpServletRequest request) {
        EditFormContext context = RequestEditContext.web(request, viewerOf(user),
                RequestEditContext.FormValues.of(title, newPage, parameters));
        List<String> fields = this.editHookService.formFields(context);
        if (!fields.isEmpty()) {
            modelAndView.addObject("editFormFields", fields);
        }
        List<String> notices = this.editHookService.notices(context);
        if (!notices.isEmpty()) {
            modelAndView.addObject("editFormNotices", notices);
        }
    }

    /**
     * 编辑冲突提示（仿 MediaWiki 的编辑冲突页）：你的基准版本已被他人推进时不自动合并、不直接覆盖；
     * 页面展示「你的提交」与「服务器最新版本」的差异，你的文本完整保留在编辑框中，
     * 手动合并后再次保存即可（基准版本已更新为最新，不会循环冲突）。
     */
    private ModelAndView conflictNoticeView(String title, WikiPage page, WikiUser user,
                                            String submittedContent, Integer section, String comment) {
        WikiRevision latestRevision = this.wikiPageService.getLatestRevision(page.getId()).orElse(null);
        String latestContent = latestRevision == null
                ? this.wikiPageService.getLatestContent(page.getId()).orElse("")
                : latestRevision.getContent();
        String latestForDiff = latestContent;
        String structureChanged = null;
        if (section != null) {
            String latestSection = WikiSections.extract(latestContent, section);
            if (latestSection == null) {
                structureChanged = message("editConflict.structureChanged");
            } else {
                latestForDiff = latestSection;
            }
        }

        String editorName = latestRevision == null || latestRevision.getUserId() == null
                ? message("editConflict.anonymous")
                : this.userService.usernames(List.of(latestRevision.getUserId()))
                        .getOrDefault(latestRevision.getUserId(), message("editConflict.anonymous"));
        String timeText = latestRevision == null ? ""
                : WikiTime.format(latestRevision.getCreateAt(), WikiTime.DEFAULT_DATE_TIME_PATTERN);
        String latestComment = latestRevision == null ? "" : Strings.trimToEmpty(latestRevision.getComment());
        String intro = latestComment.isEmpty()
                ? message("editConflict.introNoComment", editorName, timeText)
                : message("editConflict.intro", editorName, timeText, latestComment);

        String heading = null;
        if (section != null) {
            List<WikiSections.Section> submittedSections = WikiSections.parse(submittedContent);
            if (!submittedSections.isEmpty()) {
                heading = WikiSections.displayHeading(submittedSections.get(0).heading());
            }
        }

        ModelAndView modelAndView = new ModelAndView("wiki/edit");
        modelAndView.addObject("page", page);
        modelAndView.addObject("wikiTitle", title);
        modelAndView.addObject("pageUrl", WikiUrls.pagePath(title));
        modelAndView.addObject("activeTab", "edit");
        modelAndView.addObject("isNewPage", false);
        modelAndView.addObject("editingOldRevision", null);
        modelAndView.addObject("editHeading", message("edit.heading", title));
        modelAndView.addObject("codeLanguage", codeLanguage(title));
        modelAndView.addObject("showEditToolbar", codeLanguage(title) == null);
        modelAndView.addObject("widgetPage", WikiNamespaces.isWidget(title));
        modelAndView.addObject("editContent", submittedContent);
        modelAndView.addObject("editSection", section);
        modelAndView.addObject("editSectionHeading", heading == null ? message("edit.leadSection") : heading);
        modelAndView.addObject("editSummary", comment);
        modelAndView.addObject("canBotEdit", user != null
                && this.permissionService.has(user.getId(), PermissionService.GROUP_BOT));
        // 保存基准改成最新版本：再次保存即生成新版本（不会反复回到冲突页）
        modelAndView.addObject("baseRevisionId", page.getLatestRevisionId());
        modelAndView.addObject("editConflictNotice", intro);
        modelAndView.addObject("editConflictStructureChanged", structureChanged);
        modelAndView.addObject("editConflictDiffRows", DiffRows.build(submittedContent, latestForDiff));
        modelAndView.addObject("editConflictLatestUrl", WikiUrls.pagePath(title));
        modelAndView.addObject("editConflictDiffUrl", latestRevision == null ? null
                : WikiUrls.pagePath(title) + "?diff=prev&oldid=" + latestRevision.getId());
        modelAndView.addObject("editConflictEditLatestUrl", WikiUrls.pagePath(title) + "?action=edit");
        addPagePermissionAttributes(modelAndView, title, page, user);
        applyUserToolsSidebar(modelAndView, title, user);
        return modelAndView;
    }

    /**
     * 保存前钩子登记的变更标签 → 版本的 {@code tags} 字段（逗号分隔；没有标签 = 空串）。
     * <p>
     * 迁移前这里是滥用过滤器 {@code Outcome.tags()}，现在由任意 {@code EditGuard} 经
     * {@code EditContext.addTag(…)} 登记（滥用过滤器插件用它实现 {@code tag} 操作）。
     */
    private static String changeTags(RequestEditContext context) {
        return context == null ? "" : String.join(",", context.tags());
    }

    /**
     * 保存前钩子（{@code EditGuard}）拒绝保存：回到编辑表单（保留已填内容与摘要），把钩子给的说明显示在
     * 提示区，并重新渲染插件的表单字段（验证码会因此换一道新题）。
     */
    private ModelAndView renderEditHookFailure(String title, WikiPage page, WikiUser user,
                                               String submittedContent, Integer section, String comment,
                                               String errorMessage, HttpServletRequest request) {
        String heading = null;
        List<WikiSections.Section> submittedSections = WikiSections.parse(submittedContent);
        if (!submittedSections.isEmpty()) {
            heading = WikiSections.displayHeading(submittedSections.get(0).heading());
        }

        ModelAndView modelAndView = new ModelAndView("wiki/edit");
        modelAndView.addObject("page", page);
        modelAndView.addObject("wikiTitle", title);
        modelAndView.addObject("pageUrl", WikiUrls.pagePath(title));
        modelAndView.addObject("activeTab", "edit");
        modelAndView.addObject("isNewPage", page == null);
        modelAndView.addObject("editingOldRevision", null);
        modelAndView.addObject("editHeading", message(page == null ? "edit.createHeading" : "edit.heading", title));
        modelAndView.addObject("codeLanguage", codeLanguage(title));
        modelAndView.addObject("showEditToolbar", codeLanguage(title) == null);
        modelAndView.addObject("widgetPage", WikiNamespaces.isWidget(title));
        modelAndView.addObject("editContent", submittedContent);
        modelAndView.addObject("editSection", section);
        modelAndView.addObject("editSectionHeading", heading == null ? message("edit.leadSection") : heading);
        modelAndView.addObject("editSummary", comment);
        modelAndView.addObject("canBotEdit", user != null
                && this.permissionService.has(user.getId(), PermissionService.GROUP_BOT));
        modelAndView.addObject("baseRevisionId", page == null ? null : page.getLatestRevisionId());
        // 拒绝原因（如验证码答错）+ 重新渲染插件字段（验证码会换一道新题）
        if (!Strings.isBlank(errorMessage)) {
            modelAndView.addObject("editHookError", errorMessage);
        }
        applyEditFormExtensions(modelAndView, title, page == null, user,
                RequestEditContext.formParameters(request), request);
        addPagePermissionAttributes(modelAndView, title, page, user);
        applyUserToolsSidebar(modelAndView, title, user);
        return modelAndView;
    }

    /** {@code section} 参数：空 / 非法 / 负数 → null（整页编辑）。 */
    private static Integer parseSection(String value) {
        String text = Strings.trimToEmpty(value);
        if (text.isEmpty()) {
            return null;
        }
        try {
            int section = Integer.parseInt(text);
            return section >= 0 ? section : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** 保存后跳到该段落的锚点（标题 → #锚点；没有标题则回页首）。 */
    private static String sectionFragment(String mergedContent, int section) {
        String heading = WikiSections.headingOf(mergedContent, section);
        return heading == null ? "" : "#" + encodeQueryValue(WikiSections.anchorOf(heading));
    }

    /** 被封禁时的编辑提示页（仿 MediaWiki：说明原因 / 到期 / 封禁者；仍然可以阅读）。 */
    private ModelAndView renderBlocked(String title, WikiPage page, WikiUser user, WikiBlock block) {
        ModelAndView modelAndView = new ModelAndView("wiki/blocked");
        modelAndView.addObject("page", page);
        modelAndView.addObject("wikiTitle", title);
        modelAndView.addObject("pageUrl", WikiUrls.pagePath(title));
        modelAndView.addObject("activeTab", "edit");
        modelAndView.addObject("block", block);
        Long blockerId = block.getByUserId();
        modelAndView.addObject("blockerName",
                blockerId == null ? null : this.userService.usernames(List.of(blockerId)).get(blockerId));
        addPagePermissionAttributes(modelAndView, title, page, user);
        return modelAndView;
    }

    /** 查看源代码（未登录 / 没有编辑（创建）权限 / 受保护页面 / 他人用户页时）。 */
    private ModelAndView renderSource(String title, WikiPage page, WikiUser user) {
        return renderSource(title, page, user, null);
    }

    /** 查看源代码；{@code restriction} 非空时作为提示文案（标题黑名单 / 邮箱白名单等不允许保存的原因）。 */
    private ModelAndView renderSource(String title, WikiPage page, WikiUser user, String restriction) {
        String content = page == null ? "" : this.wikiPageService.getLatestContent(page.getId()).orElse("");

        boolean protectedLocked = page != null
                && Boolean.TRUE.equals(page.getProtectedPage())
                && !isAdmin(user);
        boolean widgetPage = WikiNamespaces.isWidget(title);
        boolean gadgetPage = WikiNamespaces.isGadget(title)
                || title.equalsIgnoreCase("WikiKernel:Gadgets-definition");
        String notice = restriction;
        if (notice == null && !protectedLocked && user != null && !widgetPage) {
            // 有查看权限但规则不允许编辑 / 创建：给出对应提示（未登录时模板显示登录链接）
            if (CodePages.isInterfacePage(title)) {
                // CSS / JS 页面：默认只有界面管理员能编辑（仿 MediaWiki interface-admin）
                notice = message("source.interfaceAdmin");
            } else if (!canModifyUserPage(title, user)) {
                notice = message("source.noEditOtherUserPage");
            } else {
                notice = message(page == null ? "source.noCreatePermission" : "source.noEditPermission");
            }
        }

        ModelAndView modelAndView = new ModelAndView("wiki/source");
        modelAndView.addObject("page", page);
        modelAndView.addObject("wikiTitle", title);
        modelAndView.addObject("pageUrl", WikiUrls.pagePath(title));
        modelAndView.addObject("activeTab", "edit");
        modelAndView.addObject("sourceText", content);
        modelAndView.addObject("codeLanguage", codeLanguage(title));
        modelAndView.addObject("widgetPage", widgetPage);
        modelAndView.addObject("gadgetPage", gadgetPage);
        modelAndView.addObject("sourceLogin", user == null);
        modelAndView.addObject("sourceProtected", protectedLocked);
        modelAndView.addObject("sourceMissing", page == null);
        modelAndView.addObject("sourceNotice", notice);
        addPagePermissionAttributes(modelAndView, title, page, user);
        applyUserToolsSidebar(modelAndView, title, user);
        return modelAndView;
    }

    private boolean isAdmin(WikiUser user) {
        return user != null && this.permissionService.has(user.getId(), PermissionService.GROUP_ADMIN);
    }

    /** 没有查看权限时统一 403（页面 / 特殊页 / 原始内容）；标题黑 / 白名单页按「页面不存在」处理（404）。 */
    private void requireRead(String title, WikiUser user) {
        if (!this.accessControlService.canRead(title, user)) {
            if (ConfigPages.hiddenWhenUnreadable(title)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, message("error.pageMissing", title));
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message("error.readForbidden"));
        }
    }

    /** 页签/工具需要的权限模型属性（编辑 / 查看源代码 / 移动 / 保护 / 删除；全部来自可配置的访问规则）。 */
    private void addPagePermissionAttributes(ModelAndView modelAndView, String title, WikiPage page, WikiUser user) {
        boolean canMove = this.accessControlService.can(AccessAction.MOVE, title, user)
                && (page == null || !Boolean.TRUE.equals(page.getProtectedPage()) || isAdmin(user));
        modelAndView.addObject("canEditPage", canEditPage(title, page, user));
        modelAndView.addObject("canMove", canMove);
        modelAndView.addObject("canProtect", this.accessControlService.can(AccessAction.PROTECT, title, user));
        // 删除：与移动一致，受保护页面只有管理员能删
        modelAndView.addObject("canDelete",
                this.accessControlService.can(AccessAction.DELETE, title, user)
                        && (page == null || !Boolean.TRUE.equals(page.getProtectedPage()) || isAdmin(user)));
        // 讨论页签（仿 MediaWiki）：普通页面 ↔ 讨论命名空间页面
        String talkTitle = TalkPages.talkTitle(title);
        boolean talkPage = TalkPages.isTalk(title);
        modelAndView.addObject("isTalkPage", talkPage);
        modelAndView.addObject("talkPageUrl", talkTitle == null ? null : WikiUrls.pagePath(talkTitle));
        modelAndView.addObject("subjectPageUrl",
                talkPage ? WikiUrls.pagePath(TalkPages.subjectTitle(title)) : null);
        // 监视页签：登录用户显示「监视 / 取消监视」（匿名显示登录链接）
        modelAndView.addObject("watched",
                user != null && this.watchService.isWatched(user.getId(), title));
        modelAndView.addObject("showEditWatchOptions", user != null);
        modelAndView.addObject("editWatchChecked", user != null && this.watchService.isWatched(user.getId(), title));
        // 页签：可视化编辑（?veaction=edit）仅在页面可编辑且是 Wikitext 页面时显示
        modelAndView.addObject("canVisualEdit",
                page != null && codeLanguage(title) == null && canEditPage(title, page, user));
        modelAndView.addObject("veMode", false);
    }

    private void updateWatchAfterSave(WikiUser user, String title, boolean watchAfterSave) {
        if (user == null) {
            return;
        }
        if (watchAfterSave) {
            this.watchService.watch(user.getId(), title);
        } else {
            this.watchService.unwatch(user.getId(), title);
        }
    }

    /** 是否可编辑 / 创建：访问规则 + 受保护页面（管理员）+ 用户页归属（本人 / 管理员 / 站点设置）。 */
    private boolean canEditPage(String title, WikiPage page, WikiUser user) {
        if (!canModifyUserPage(title, user)) {
            return false;
        }
        boolean can = page == null
                ? this.accessControlService.can(AccessAction.CREATE, title, user)
                : this.accessControlService.can(AccessAction.EDIT, title, user);
        if (page != null && Boolean.TRUE.equals(page.getProtectedPage()) && !isAdmin(user)) {
            can = false;
        }
        return can;
    }

    /**
     * 保存限制（禁用标题名单 + 编辑邮箱白名单）：返回不允许保存的原因；{@code null} = 可以保存。
     * 管理员 / 行政员豁免（仿 MediaWiki 的 tboverride，避免配置写坏后无人能修）。
     */
    private String saveRestriction(String title, WikiPage page, WikiUser user) {
        TitleBlacklistService.Operation operation = page == null
                ? TitleBlacklistService.Operation.CREATE : TitleBlacklistService.Operation.EDIT;
        Optional<TitleBlacklistService.Violation> violation =
                this.titleBlacklistService.check(title, operation, user);
        if (violation.isPresent()) {
            return titleBlacklistMessage(violation.get(), title,
                    page == null ? "titleblacklist.forbidden.create" : "titleblacklist.forbidden.edit");
        }
        if (!canOverrideRestrictions(user)) {
            String email = user == null ? null : user.getEmail();
            // 编辑邮箱白名单：名单启用时只有匹配的邮箱能保存
            if (!this.emailWhitelistService.allows(email)) {
                return message("edit.emailWhitelist.forbidden");
            }
            // 编辑邮箱黑名单：命中即禁止保存（提示优先显示白名单限制）
            if (this.emailBlacklistService.blocked(email)) {
                return message("edit.emailBlacklist.forbidden");
            }
        }
        return null;
    }

    /** 自定义错误提示（规则里的 {@code errmsg=消息键}）；缺失时回退到内置消息键。 */
    private String titleBlacklistMessage(TitleBlacklistService.Violation violation, String title, String defaultKey) {
        String customKey = Strings.trimToEmpty(violation.customMessageKey());
        if (!customKey.isEmpty()) {
            String custom = this.messageSource.getMessage(customKey, new Object[]{title, violation.pattern()},
                    null, LocaleContextHolder.getLocale());
            if (custom != null) {
                return custom;
            }
        }
        return message(defaultKey, title);
    }

    /** 管理员 / 行政员豁免保存限制（仿 MediaWiki 的 tboverride）。 */
    private boolean canOverrideRestrictions(WikiUser user) {
        return user != null && this.permissionService.canManage(user.getId());
    }

    /**
     * 用户页（{@code 用户:XXX} / {@code User:XXX}）的编辑归属：
     * 本人、管理员始终可以；其他人由站点设置 {@code permissions.allow-other-user-page-edit} 控制（默认否）。
     */
    private boolean canModifyUserPage(String title, WikiUser user) {
        String owner = userNamespaceName(title);
        if (owner == null || owner.isEmpty()) {
            return true;
        }
        if (user != null && owner.equalsIgnoreCase(user.getUsername())) {
            return true;
        }
        if (isAdmin(user)) {
            return true;
        }
        return this.siteSettingsService.getBoolean(SiteSettingsService.ALLOW_OTHER_USER_PAGE_EDIT, false);
    }

    /** {@code 用户:XXX} / {@code User:XXX} → XXX；不是用户命名空间返回 null。 */
    private static String userNamespaceName(String title) {
        return namespaceName(title, USER_NAMESPACE_PREFIXES);
    }

    /**
     * 尚未创建的用户页 / 用户讨论页：普通阅读页样式（头像资料卡 + 尚未创建提示 + 创建链接），
     * 不再自动 302 到 {@code ?action=edit}；签名 / 权限页签与用户页一致。
     */
    private ModelAndView renderMissingUserPage(String title, WikiUser currentUser) {
        ModelAndView modelAndView = new ModelAndView("wiki/missing-user");
        modelAndView.addObject("page", null);
        modelAndView.addObject("wikiTitle", title);
        modelAndView.addObject("pageUrl", WikiUrls.pagePath(title));
        modelAndView.addObject("activeTab", "view");
        addPagePermissionAttributes(modelAndView, title, null, currentUser);
        applyUserToolsSidebar(modelAndView, title, currentUser);
        return modelAndView;
    }

    /**
     * TypeScript 页面的编译状态（.ts / .tsx）：
     * 有编译缓存时给出「已编译（编译器版本）」或「编译失败 + 诊断行号」，供页面顶部提示。
     * <p>
     * 源码大小上限是**实时策略**（超限不写缓存），所以这里也实时判断一次：超限时直接显示诊断，
     * 不必等浏览器去请求脚本才发现。
     */
    private void addTypeScriptNotice(ModelAndView modelAndView, WikiPage page, String content) {
        if (page == null || !TypeScriptAssetService.isTypeScriptPage(page.getTitle())
                || !this.featureService.typescriptEnabled()) {
            return;
        }
        modelAndView.addObject("typeScriptEnabled", true);
        modelAndView.addObject("typeScriptCompileOnSave", this.featureService.typescriptCompileOnSave());
        int limitKb = this.typeScriptAssetService.maxSourceKilobytes();
        String source = content == null ? "" : content;
        if (limitKb > 0 && source.length() > (long) limitKb * 1024) {
            modelAndView.addObject("typeScriptCompiled", false);
            modelAndView.addObject("typeScriptCompiler", this.typeScriptCompiler.version());
            modelAndView.addObject("typeScriptDiagnostics", List.of(
                    "源码过大（" + (source.length() / 1024) + " KB > 上限 " + limitKb
                            + " KB）：可在「特殊:权限配置 → 站点设置 → TypeScript 服务端编译」调整上限，"
                            + "-1 表示不限制。"));
            return;
        }
        this.typeScriptAssetService.cached(page.getLatestRevisionId(), TypeScriptAssetService.TARGET_JAVASCRIPT)
                .ifPresent(row -> {
                    modelAndView.addObject("typeScriptCompiled", Boolean.TRUE.equals(row.getSuccessful()));
                    modelAndView.addObject("typeScriptCompiler", row.getCompiler());
                    modelAndView.addObject("typeScriptDiagnostics",
                            Strings.isBlank(row.getDiagnostics())
                                    ? List.of()
                                    : Arrays.asList(row.getDiagnostics().split("\n")));
                });
    }

    /** 命名空间前缀匹配（大小写不敏感）→ 去掉前缀的名字；不匹配返回 null。 */
    private static String namespaceName(String title, List<String> prefixes) {
        String value = Strings.trimToEmpty(title);
        for (String prefix : prefixes) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return value.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /** 用户页 / 用户讨论页：侧栏追加「工具」箱（链入 / 贡献 / 日志 / 查看或更改用户组 / 页面信息；核查员另有核查 / 合并）。 */
    private void applyUserToolsSidebar(ModelAndView modelAndView, String title, WikiUser viewer) {
        applyUserToolsSidebar(modelAndView, title, viewer, List.of());
    }

    /**
     * 用户页 / 用户讨论页 / 普通内容页的侧栏。
     *
     * @param languageLinks 正文里的跨语言链接（{@code [[en:页面]]}）：非空时在侧栏末尾追加「语言」区块
     */
    private void applyUserToolsSidebar(ModelAndView modelAndView, String title, WikiUser viewer,
                                       List<InterwikiService.Link> languageLinks) {
        String username = userNamespaceName(title);
        if (username == null || username.isEmpty()) {
            username = namespaceName(title, USER_TALK_PREFIXES);
        }
        if (username != null && !username.isEmpty()) {
            boolean canCheckUser = viewer != null
                    && this.permissionService.has(viewer.getId(), PermissionService.GROUP_CHECKUSER);
            boolean canBlock = this.accessControlService.can(AccessAction.BLOCK, BlockSpecialPage.SPECIAL_TITLE, viewer);
            // 「发私信」页面（特殊:私信）由插件 social-profile 提供：插件未加载时不显示，避免死链
            //（插件已加载但功能开关关闭时由插件自己的页面提示，core 不认识这个开关）
            boolean canSendMessage = viewer != null && !username.equalsIgnoreCase(viewer.getUsername())
                    && this.specialWikiPageService
                    .resolve(UserToolsSidebar.MESSAGES_PAGE, Map.of()).isPresent();
            // 管理员 / 行政员看到「更改用户组」，其他人看到「查看用户组」（仿 MediaWiki）
            boolean canChangeGroups = viewer != null && this.permissionService.canManage(viewer.getId());
            modelAndView.addObject("sidebar",
                    this.userToolsSidebar.withInterlanguages(
                            this.userToolsSidebar.withUserTools(username, canCheckUser, canBlock, canSendMessage,
                                    canChangeGroups),
                            languageLinks));
            return;
        }
        // 普通内容页：工具箱的「链入页面 / 相关更改 / 上传文件 / 可打印版 / 固定链接 / 页面信息」指向当前页面（仿 MediaWiki 工具箱）
        Long latestRevisionId = this.wikiPageService.getByTitle(title)
                .map(WikiPage::getLatestRevisionId)
                .orElse(null);
        modelAndView.addObject("sidebar",
                this.userToolsSidebar.withInterlanguages(
                        this.userToolsSidebar.withPageTools(title, latestRevisionId), languageLinks));
    }

    /**
     * 代码页面语言：{@code .js → javascript}、{@code .ts / .tsx → typescript}、{@code .css → css}、
     * 微件页 {@code html}、「模块:」命名空间 {@code lua}；其它为 null（走 Wikitext 渲染）。
     * <p>
     * {@code .ts} 只是**代码内容页**（高亮 / 编辑 / 复制，和 {@code .md} 一样是惰性内容），
     * <b>不会</b>被当成脚本执行——需要浏览器执行时请写 {@code .js}（见 README「TypeScript」一节）。
     */
    private static String codeLanguage(String title) {
        String lower = Strings.trimToEmpty(title).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js")) {
            return "javascript";
        }
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return "typescript";
        }
        if (lower.endsWith(".vue")) {
            // Vue 单文件组件：用 HTML 高亮（template / script / style 三块都能正确着色）
            return "html";
        }
        if (lower.endsWith(".css")) {
            return "css";
        }
        if (WikiNamespaces.isWidget(title)) {
            return "html";
        }
        if (WikiNamespaces.isModule(title)) {
            return "lua";
        }
        return null;
    }

    /**
     * 差异对比页（仿 MediaWiki 的 DifferenceEngine）：
     * {@code ?diff=prev&oldid=N}（N 与上一版本）、{@code ?diff=next&oldid=N}（N 与下一版本）、
     * {@code ?diff=N&oldid=M}（M → N）、{@code ?diff=cur&oldid=N}（N 与当前版本）、
     * {@code ?diff=N}（没有 oldid 时旧侧取 N 的上一版本）。
     */
    private ModelAndView renderDiff(WikiPage page, Map<String, String> parameters, WikiUser user) {
        String diffParam = Strings.trimToEmpty(parameters.get("diff")).toLowerCase(Locale.ROOT);
        Long oldId = parseId(parameters.get("oldid"));

        WikiRevision newer;
        WikiRevision older;
        if ("prev".equals(diffParam)) {
            newer = requireRevision(page, oldId);
            older = this.wikiPageService.getPreviousRevision(page.getId(), newer.getId()).orElse(null);
        } else if ("next".equals(diffParam)) {
            older = requireRevision(page, oldId);
            newer = this.wikiPageService.getNextRevision(page.getId(), older.getId())
                    .orElseGet(() -> this.wikiPageService.getLatestRevision(page.getId()).orElse(older));
        } else {
            Long newId = parseId(diffParam); // 数字；'cur' / 其它文本 → null = 当前版本
            newer = newId == null
                    ? this.wikiPageService.getLatestRevision(page.getId())
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                    message("error.revisionMissing", diffParam)))
                    : requireRevision(page, newId);
            older = oldId == null || oldId <= 0
                    ? this.wikiPageService.getPreviousRevision(page.getId(), newer.getId()).orElse(null)
                    : requireRevision(page, oldId);
        }

        String oldContent = older == null ? "" : older.getContent();
        String newContent = newer.getContent();
        List<Map<String, Object>> rows = DiffRows.build(oldContent, newContent);
        boolean changed = !oldContent.equals(newContent);

        List<Long> userIds = new ArrayList<>();
        if (older != null && older.getUserId() != null) {
            userIds.add(older.getUserId());
        }
        if (newer.getUserId() != null) {
            userIds.add(newer.getUserId());
        }
        Map<Long, String> userNames = this.userService.usernames(userIds);
        String oldUserName = older == null || older.getUserId() == null
                ? null : userNames.get(older.getUserId());
        String newUserName = newer.getUserId() == null ? null : userNames.get(newer.getUserId());

        String pageUrl = WikiUrls.pagePath(page.getTitle());
        // 上一差异 / 下一差异（仿 MediaWiki 的 prevlink / nextlink）
        String prevDiffUrl = older != null
                && this.wikiPageService.getPreviousRevision(page.getId(), older.getId()).isPresent()
                ? pageUrl + "?diff=prev&oldid=" + older.getId() : null;
        String nextDiffUrl = Objects.equals(newer.getId(), page.getLatestRevisionId())
                ? null : pageUrl + "?diff=next&oldid=" + newer.getId();

        ModelAndView modelAndView = new ModelAndView("wiki/diff");
        modelAndView.addObject("page", page);
        modelAndView.addObject("wikiTitle", page.getTitle());
        modelAndView.addObject("pageUrl", pageUrl);
        modelAndView.addObject("historyUrl", pageUrl + "?action=history");
        modelAndView.addObject("activeTab", "history");
        modelAndView.addObject("older", older);
        modelAndView.addObject("newer", newer);
        modelAndView.addObject("oldUserName", oldUserName);
        modelAndView.addObject("newUserName", newUserName);
        modelAndView.addObject("prevDiffUrl", prevDiffUrl);
        modelAndView.addObject("nextDiffUrl", nextDiffUrl);
        modelAndView.addObject("diffRows", rows);
        modelAndView.addObject("diffEmpty", !changed);
        modelAndView.addObject("pageCss", sanitizeCss(
                this.wikiPageService.getCSS(page.getTitle(), activeSkinId(user))));
        addPagePermissionAttributes(modelAndView, page.getTitle(), page, user);
        applyUserToolsSidebar(modelAndView, page.getTitle(), user);
        return modelAndView;
    }

    /** 取指定 ID 的版本（必须属于该页面且未删除）；ID 为空 / <=0 时取当前版本。 */
    private WikiRevision requireRevision(WikiPage page, Long revisionId) {
        if (revisionId == null || revisionId <= 0) {
            return this.wikiPageService.getLatestRevision(page.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            message("error.revisionMissing", revisionId)));
        }
        return this.wikiPageService.getRevision(revisionId)
                .filter(revision -> Objects.equals(revision.getPageId(), page.getId()))
                .filter(revision -> !Boolean.TRUE.equals(revision.getDeleted()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        message("error.revisionMissing", revisionId)));
    }

    /**
     * 访问「已被删除的页面」时的提示页（仿 MediaWiki）：
     * 说明「此页面已被删除」，管理员额外可以**找回最近版本的源代码**并前往「特殊:已删除页面」恢复。
     */
    private ModelAndView renderDeletedPage(WikiPage page, WikiUser currentUser) {
        ModelAndView modelAndView = new ModelAndView("wiki/deleted");
        boolean canRestore = currentUser != null && this.permissionService.canManage(currentUser.getId());
        modelAndView.addObject("page", page);
        modelAndView.addObject("wikiTitle", page.getTitle());
        modelAndView.addObject("pageUrl", WikiUrls.pagePath(page.getTitle()));
        modelAndView.addObject("activeTab", "view");
        modelAndView.addObject("footerLastEdited", page.getUpdateAt());
        // 页签（页面 / 讨论 / 编辑 / 历史…）：page 传 null —— 编辑页签表示「重新创建本页面」
        addPagePermissionAttributes(modelAndView, page.getTitle(), null, currentUser);
        applyUserToolsSidebar(modelAndView, page.getTitle(), currentUser);
        modelAndView.addObject("canRestore", canRestore);
        if (canRestore) {
            // 找回源代码：最近版本原文（只给能恢复的人看，与 MediaWiki 只对管理员展示被删内容一致）
            modelAndView.addObject("deletedContent",
                    this.wikiPageService.getLatestContent(page.getId()).orElse(""));
            modelAndView.addObject("restoreUrl",
                    WikiUrls.pagePath("特殊:已删除页面") + "?page=" + encodeQueryValue(page.getTitle()));
        }
        return modelAndView;
    }

    private ModelAndView renderHistory(String title, HttpServletRequest request) {
        WikiPage page = this.wikiPageService.getVisibleByTitle(title).orElse(null);
        if (page == null) {
            // 已删除的页面：给「此页面已被删除」提示（管理员可找回最近的源代码并恢复；仿 MediaWiki）
            Optional<WikiPage> deletedPage = this.wikiPageService.findDeletedByTitle(title);
            if (deletedPage.isPresent()) {
                return renderDeletedPage(deletedPage.get(), UserSession.current(request));
            }
            if (this.wikiPageService.getByTitle(title).isPresent()) {
                // 页面存在但被隐藏：仍然 404，不暴露存在性
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, message("error.pageMissing", title));
            }
            // 页面不存在：404（自定义 404 页；创建入口在 404 页的「创建本页面」蓝链）
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message("error.pageMissing", title));
        }
        WikiUser currentUser = UserSession.current(request);
        requireRead(title, currentUser);
        List<WikiRevision> revisions = this.wikiPageService.getHistory(page.getId());
        List<Long> userIds = revisions.stream()
                .map(WikiRevision::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        ModelAndView modelAndView = new ModelAndView("wiki/history");
        modelAndView.addObject("page", page);
        modelAndView.addObject("wikiTitle", title);
        modelAndView.addObject("pageUrl", WikiUrls.pagePath(title));
        modelAndView.addObject("activeTab", "history");
        modelAndView.addObject("revisions", revisions);
        modelAndView.addObject("userNames", this.userService.usernames(userIds));
        addPagePermissionAttributes(modelAndView, title, page, currentUser);
        applyUserToolsSidebar(modelAndView, title, currentUser);
        return modelAndView;
    }

    // ==================== 特殊页 & 工具 ====================

    private ModelAndView renderSpecialPage(SpecialPageMatch match, HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        // 特殊页统一用规范名做访问控制（别名 / 不同前缀共享同一条规则）
        requireRead("特殊:" + match.page().name(), user);
        SpecialPageRequest specialRequest = new SpecialPageRequest(
                match.name(), match.parameters(), user);
        // 工具箱条目按当前特殊页生成（可打印版 / 固定链接 / 页面信息都指回这个特殊页）
        String specialTitle = "特殊:" + match.page().name();
        String specialPath = request.getRequestURI();

        String html = match.page().render(specialRequest);
        if (html != null) {
            ModelAndView modelAndView = new ModelAndView("special/view");
            modelAndView.addObject("title", match.page().displayName());
            modelAndView.addObject("isSpecialPage", true);
            modelAndView.addObject("contentHtml", html);
            modelAndView.addObject("sidebar",
                    this.userToolsSidebar.withSpecialPageTools(specialTitle, specialPath));
            return modelAndView;
        }

        ModelAndView modelAndView = new ModelAndView(match.page().template());
        modelAndView.addObject("title", match.page().displayName());
        modelAndView.addObject("isSpecialPage", true);
        modelAndView.addObject("sidebar",
                this.userToolsSidebar.withSpecialPageTools(specialTitle, specialPath));
        match.page().populate(specialRequest, modelAndView.getModel());
        // populate() 设置跳转键时直接 302（如“创建页面”输入标题后跳到编辑页）
        Object redirect = modelAndView.getModel().get(SpecialPage.REDIRECT_MODEL_ATTRIBUTE);
        if (redirect instanceof String path && !path.isBlank()) {
            return new ModelAndView("redirect:" + path);
        }
        return modelAndView;
    }

    /**
     * 把页面样式过滤成可直接内联到 {@code <head>} 的 CSS 文本
     * （已转义 {@code <}，防止 CSS 里写 {@code </style>} 提前闭合标签）。
     */
    private List<String> sanitizeCss(List<String> cssContents) {
        return cssContents.stream()
                .map(CssSanitizer::sanitize)
                .filter(css -> !css.isBlank())
                .toList();
    }

    private static Long parseId(String value) {
        if (Strings.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 标题非法时的本地化提示（带上命中的字符 / 片段）。 */
    private String titleErrorMessage(WikiTitles.TitleError error) {
        return message(error.messageKey(), error.detail());
    }

    /** 把两段 PathVariable 拼回完整标题：{@code /wiki/首页/样式.css} → {@code 首页/样式.css}。 */
    private static String fullTitle(String title, String rest) {
        if (rest == null || rest.isEmpty()) {
            return title;
        }
        String normalized = rest.startsWith("/") ? rest.substring(1) : rest;
        return title + "/" + normalized;
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}