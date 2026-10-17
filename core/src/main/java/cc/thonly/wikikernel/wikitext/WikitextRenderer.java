package cc.thonly.wikikernel.wikitext;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.extension.BehaviorSwitch;
import cc.thonly.wikikernel.actionapi.ActionApiNamespaces;
import cc.thonly.wikikernel.common.extension.FileEmbedCall;
import cc.thonly.wikikernel.common.extension.FileEmbedRenderer;
import cc.thonly.wikikernel.common.extension.MagicWord;
import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.lua.LuaModuleService;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.InterwikiService;
import cc.thonly.wikikernel.service.PageExistenceService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.FileTitles;
import cc.thonly.wikikernel.web.MediaPlayerHtml;
import cc.thonly.wikikernel.web.WikiFileEmbedCall;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.WikiKernelInfo;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import cc.thonly.wikikernel.service.WikiStatsService;

/**
 * 轻量 Wikitext → HTML 渲染器（MediaWiki 风格常用子集）：
 * <ul>
 *     <li>模板：{@code {{模板名|a=1|b=2}}}、位置参数、{@code {{{参数|默认值}}}、递归与循环保护；
 *     支持 {@code <noinclude>}/{@code <includeonly>}/{@code <onlyinclude>}；</li>
 *     <li>解析器函数：{@code #if} / {@code #ifeq} / {@code #switch}，魔术字 PAGENAME/FULLPAGENAME/NAMESPACE/!；</li>
 *     <li>块级：段落、{@code = 标题 =}、{@code ----}、{@code *}/{@code #} 列表、{@code {| 表格 |}}、
 *     {@code <pre>}/{@code <nowiki>}/{@code <syntaxhighlight>}（语法高亮）/{@code <source>}、
 *     {@code <poem>}（保留换行）、{@code <markdown>}（Markdown 渲染，见 {@link MarkdownRenderer}）、
 *     {@code <inputbox>}（创建 / 搜索表单）；</li>
 *     <li>行内：{@code '''粗'''}、{@code ''斜''}、{@code [[内链|文字]]}（缺失页面红链）、{@code [url 外链]}、
 *     {@code [[文件:名称.png|thumb|说明|200px]]}（图片嵌入：缩略图 / 宽度 / 对齐 / alt / link）、
 *     白名单 HTML（含 {@code <br>}/{@code <hr>}/{@code <iframe>}）。</li>
 * </ul>
 * 插件扩展点：{@code {{#名字:参数}}} 与 {@code {{名字:参数}}}（如页面横幅的 {@code {{PAGEBANNER:文件名}}}）
 * 都会派发给注册的 {@link ParserFunction}；未注册的名字仍按模板解析。
 * 所有用户文本默认转义，内嵌 HTML 经过 {@link HtmlSanitizer} 白名单过滤。
 */
@Component
public class WikitextRenderer {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WikitextRenderer.class);

    private static final Pattern HEADING = Pattern.compile("^\\s*(={1,6})\\s*(.+?)\\s*\\1\\s*$");
    private static final Pattern HR = Pattern.compile("^\\s*-{4,}\\s*$");
    private static final Pattern LIST = Pattern.compile("^([*#:;]+)(.*)$");
    private static final Pattern PLACEHOLDER_LINE = Pattern.compile("^\\uE000\\d+\\uE000$");
    private static final Pattern TAG = Pattern.compile("</?\\s*[a-zA-Z][^<>]*>");
    private static final Pattern BOTH = Pattern.compile("'''''(.*?)'''''", Pattern.DOTALL);
    private static final Pattern BOLD = Pattern.compile("'''(.*?)'''", Pattern.DOTALL);
    private static final Pattern ITALIC = Pattern.compile("''(.*?)''", Pattern.DOTALL);
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\[\\]|]+)(?:\\|([^\\[\\]]*))?\\]\\]");
    private static final Pattern EXTERNAL_LINK = Pattern.compile(
            "\\[((?:(?:https?|ftp)://|/)[^\\s\\]]+)(?:\\s+([^\\]]*))?\\]");
    private static final Pattern NOWIKI = Pattern.compile("<nowiki>(.*?)</nowiki>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** {@code <syntaxhighlight lang="java">…</syntaxhighlight>}（属性里取 lang= 做语法高亮）。 */
    private static final Pattern SYNTAX = Pattern.compile(
            "<syntaxhighlight(\\s[^>]*)?>(.*?)</syntaxhighlight>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** {@code <source lang="java">…</source>}：SyntaxHighlight 的别名标签。 */
    private static final Pattern SOURCE = Pattern.compile(
            "<source(\\s[^>]*)?>(.*?)</source>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern PRE = Pattern.compile("<pre(?:\\s[^>]*)?>(.*?)</pre>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** {@code <markdown>…</markdown>}（仿 MediaWiki 的 Markdown 类扩展）：标签内容按 Markdown 渲染。 */
    private static final Pattern MARKDOWN = Pattern.compile(
            "<markdown(\\s[^>]*)?>(.*?)</markdown\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** {@code <poem>…</poem>}（仿 Poem 扩展）：保留换行，内部支持行内 wikitext。 */
    private static final Pattern POEM = Pattern.compile(
            "<poem(?:\\s[^>]*)?>(.*?)</poem>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** {@code <gallery>文件:甲.jpg|说明（每行一张）</gallery>}（仿 MediaWiki 画廊）。 */
    private static final Pattern GALLERY = Pattern.compile(
            "<gallery(\\s[^>]*)?>(.*?)</gallery\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** {@code <indicator name="…">…</indicator>}（仿 MediaWiki 页面状态指示器，显示在标题右上角）。 */
    private static final Pattern INDICATOR = Pattern.compile(
            "<indicator(\\s[^>]*)?>(.*?)</indicator\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** {@code lang="…"} / {@code lang=…} 属性（语法高亮用）。 */
    private static final Pattern LANG_ATTRIBUTE = Pattern.compile(
            "\\blang\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s/>]+))", Pattern.CASE_INSENSITIVE);
    /** HTML 注释（{@code <!-- … -->}）：仿 MediaWiki，渲染时整段去掉（nowiki / pre 里已被保护，不受影响）。 */
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern ATTR = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*(?:=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?");

    /** [[文件:...|200px]] 的宽度。 */
    private static final Pattern FILE_WIDTH = Pattern.compile("^(\\d+)\\s*(?:x\\s*\\d+)?\\s*px$", Pattern.CASE_INSENSITIVE);

    /** [[文件:X.webm|start=10]] 这类命名参数（交给插件渲染器，如多媒体预览的播放起点）。 */
    private static final Pattern NAMED_OPTION = Pattern.compile("^[A-Za-z_][-A-Za-z0-9_]*\\s*=.*$", Pattern.DOTALL);

    /** 目录占位符（渲染时插在第一个标题前，最后替换成目录 HTML 或删除）。 */
    private static final String TOC_PLACEHOLDER = "\uE002";

    /** 至少这么多个标题才自动显示目录（MediaWiki 规则；__TOC__ / __FORCETOC__ 可强制）。 */
    private static final int TOC_MIN_HEADINGS = 4;

    /** {@code {{DISPLAYTITLE:标题}}}（可选 {@code |noerror} 等修饰参数）。 */
    private static final Pattern DISPLAY_TITLE = Pattern.compile(
            "\\{\\{\\s*DISPLAYTITLE\\s*:\\s*([^}|]+?)\\s*(?:\\|[^}]*)?}}", Pattern.CASE_INSENSITIVE);

    /** {@code name="…"} / {@code name='…'} / {@code name=…} 属性。 */
    private static final Pattern NAME_ATTRIBUTE =
            Pattern.compile("\\bname\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s/>]+))", Pattern.CASE_INSENSITIVE);

    private final WikitextProperties properties;
    private final TemplateResolver templateResolver;
    private final MessageSource messageSource;
    private final FileService fileService;
    private final FeatureService featureService;

    /**
     * {@code <markdown>} 标签的解析器：无状态、无外部依赖，构造器里直接 new，
     * 不必进 Spring 容器（core 的单元测试都用两参数构造器，不能让这个依赖变成必需的注入项）。
     */
    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();

    /** 插件管理器（插件提供的解析器函数 / 魔术字 / 标签等扩展点）；独立测试构造时为空。 */
    @Autowired(required = false)
    private PluginManager pluginManager;

    /** 当前线程正在进行的渲染上下文（渲染是同步的；嵌套渲染用保存 / 恢复）。 */
    private static final ThreadLocal<RenderContextState> CURRENT = new ThreadLocal<>();

    /** 站内统计（{{NUMBEROF*}} 魔术字用）；独立测试构造时为空。 */
    @Autowired(required = false)
    private WikiStatsService wikiStatsService;

    /** 页面存在性（红链判断）；独立测试构造时为空（不标红）。 */
    @Autowired(required = false)
    private PageExistenceService pageExistenceService;

    /** 页面 / 版本查询（REVISIONTIMESTAMP / REVISIONUSER 魔术字用）；独立测试构造时为空。 */
    @Autowired(required = false)
    private WikiPageService wikiPageService;

    /** 用户名查询（REVISIONUSER 魔术字用）；独立测试构造时为空。 */
    @Autowired(required = false)
    private UserService userService;

    /** Lua 模块（{{#invoke:…}}）；独立测试构造时为空（显示「未启用」错误）。 */
    @Autowired(required = false)
    private LuaModuleService luaModuleService;

    /** 跨 Wiki 前缀（{@code [[w:页面]]} / {@code [[en:页面]]}，仿 MediaWiki Interwiki）；独立测试构造时为空（按普通链接处理）。 */
    @Autowired(required = false)
    private InterwikiService interwikiService;

    @Autowired
    public WikitextRenderer(WikitextProperties properties, TemplateResolver templateResolver,
                            MessageSource messageSource, FileService fileService,
                            FeatureService featureService) {
        this.properties = properties;
        this.templateResolver = templateResolver;
        this.messageSource = messageSource;
        this.fileService = fileService;
        this.featureService = featureService;
    }

    /** 测试 / 独立使用：没有 MessageSource、文件服务与功能开关时用内置兜底。 */
    public WikitextRenderer(WikitextProperties properties, TemplateResolver templateResolver) {
        this(properties, templateResolver, null, null, null);
    }

    /**
     * 页面内错误提示（本地化 + 转义）：{@code <span class="wiki-error">…</span>}。
     * <p>
     * 没有 {@link MessageSource}（单元测试等独立场景）时**显示翻译键本身**，不回落中文。
     */
    private String error(String key, Object... args) {
        String message = messageSource == null
                ? key
                : messageSource.getMessage(key, args, null, LocaleContextHolder.getLocale());
        return "<span class=\"wiki-error\">" + HtmlUtils.htmlEscape(message) + "</span>";
    }

    /**
     * 渲染结果：HTML + 分类（已从正文剥离）+ {@code {{DISPLAYTITLE:}}}} 指定的显示标题（可空）
     * + 跨语言链接（{@code [[en:页面]]}，已从正文剥离，显示在侧栏「语言」；仿 MediaWiki 的跨语言链接）。
     */
    public record RenderResult(String html, List<WikiLinkScanner.CategoryRef> categories,
                               String displayTitle, List<InterwikiService.Link> interwikiLinks) {

        /** 不关心跨语言链接的调用方（预览 / API / 测试）用这个简写。 */
        public RenderResult(String html, List<WikiLinkScanner.CategoryRef> categories, String displayTitle) {
            this(html, categories, displayTitle, List.of());
        }
    }

    /** 渲染页面正文。 */
    public String render(String wikitext, String pageTitle) {
        return renderWithMeta(wikitext, pageTitle).html();
    }

    /** 渲染页面正文并返回元信息（分类；模板展开后收集，模板里的分类同样生效）。 */
    public RenderResult renderWithMeta(String wikitext, String pageTitle) {
        return renderWithMeta(wikitext, pageTitle, createContext(pageTitle));
    }

    /**
     * 创建一次渲染的上下文：插件解析器函数 / 魔术字 / 标签等扩展点在这里索引；
     * 调用方可在渲染后从上下文读取页面属性（{@link WikiRenderContext#properties()}）。
     */
    public WikiRenderContext createContext(String pageTitle) {
        List<ParserFunction> functions = List.of();
        List<MagicWord> magicWords = List.of();
        List<BehaviorSwitch> switches = List.of();
        List<ParserTag> tags = List.of();
        List<FileEmbedRenderer> fileEmbeds = List.of();
        if (pluginManager != null) {
            functions = pluginManager.extensions(ParserFunction.class);
            magicWords = pluginManager.extensions(MagicWord.class);
            switches = pluginManager.extensions(BehaviorSwitch.class);
            tags = pluginManager.extensions(ParserTag.class);
            fileEmbeds = pluginManager.extensions(FileEmbedRenderer.class)
                    .stream()
                    .sorted(java.util.Comparator.comparingInt(
                            FileEmbedRenderer::order))
                    .toList();
        }
        return new RenderContextState(pageTitle, functions, magicWords, switches, tags, fileEmbeds);
    }

    /**
     * 使用调用方提供的渲染上下文渲染页面正文（渲染结束后可读取页面属性 / 渲染变量）。
     * 传入的上下文不是 core 的实现时会自动新建一个。
     */
    public RenderResult renderWithMeta(String wikitext, String pageTitle, WikiRenderContext context) {
        RenderContextState state = context instanceof RenderContextState value
                ? value
                : (RenderContextState) createContext(pageTitle);
        RenderContextState previous = CURRENT.get();
        CURRENT.set(state);
        try {
            return renderInternal(wikitext, pageTitle);
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** 渲染实现（上下文在 {@link #CURRENT} 里）。 */
    private RenderResult renderInternal(String wikitext, String pageTitle) {
        if (wikitext == null || wikitext.isEmpty()) {
            return new RenderResult("", List.of(), null);
        }
        // {{DISPLAYTITLE:标题}}：改写页面显示标题（从正文剥离；支持 |noerror 等修饰参数）
        String displayTitle = null;
        Matcher display = DISPLAY_TITLE.matcher(wikitext);
        if (display.find()) {
            displayTitle = display.group(1).trim();
            wikitext = display.replaceFirst("");
        }
        final String finalDisplayTitle = displayTitle;
        String direct = prepareDirect(wikitext);
        List<String> preBlocks = new ArrayList<>();
        String protectedText = protectPreBlocks(direct, preBlocks);
        List<String> trustedBlocks = new ArrayList<>();
        // 页面横幅（{{PAGEBANNER:文件名|caption=说明}} 与 <exclude-banner-page />）已迁到插件 page-banner：
        // 前者在展开时由插件注册的解析器函数写页面属性，后者由插件注册的同名标签写属性，
        // 横幅 HTML 由插件在页面显示阶段生成（core 只提供 #page-banner 容器）
        String expanded = expand(protectedText, null, pageTitle == null ? "" : pageTitle,
                0, new LinkedHashSet<>(), trustedBlocks);
        // 插件延迟占位（Variables 的 #var_final）：解析结束后求值并展开（放在分类剥离之前）
        expanded = resolveDeferred(expanded, trustedBlocks);
        List<WikiLinkScanner.CategoryRef> categories = new ArrayList<>();
        String withoutCategories = WikiLinkScanner.stripCategories(expanded, categories);
        // 跨语言链接（[[en:页面]]，仿 MediaWiki 的跨语言链接）：从正文剥离，收集到侧栏「语言」
        List<InterwikiService.Link> interwikiLinks = new ArrayList<>();
        withoutCategories = stripLanguageLinks(withoutCategories, interwikiLinks);
        // HTML 注释整段去掉（nowiki / pre / 微件可信块此时已是占位符，不受影响）
        withoutCategories = HTML_COMMENT.matcher(withoutCategories).replaceAll("");
        // 插件标签（expandBody=true，如 <seo>）：正文已展开，提取为可信块（不进 HTML 白名单）
        withoutCategories = extractPluginTags(withoutCategories, trustedBlocks);
        // <poem>…</poem>（仿 Poem 扩展）：换行保留（→ <br>），内部支持行内 wikitext；整段作为可信块还原
        withoutCategories = extractPoem(withoutCategories, trustedBlocks);
        // <gallery>…</gallery>（仿 MediaWiki 画廊）：每行一张图，整段作为可信块
        withoutCategories = extractGallery(withoutCategories, trustedBlocks);
        // <indicator name="…">…</indicator>（仿 MediaWiki 页面状态指示器）：剥离并写页面属性 indicator-<名字>
        withoutCategories = extractIndicators(withoutCategories);

        // 目录魔术字：__NOTOC__ 关闭；__TOC__ / __FORCETOC__ 强制（默认 ≥4 个标题自动显示）
        boolean forceToc = withoutCategories.contains("__TOC__")
                || withoutCategories.contains("__FORCETOC__");
        boolean noToc = withoutCategories.contains("__NOTOC__");
        // 插件行为开关（如 __NOTITLE__ / __无标题__）：剥离并通知插件（常写页面属性）
        withoutCategories = applyPluginBehaviorSwitches(withoutCategories);
        // <comment />（Comments 扩展）已迁到插件 comments：core 不再剥离该标签，
        // 插件注册的 ParserTag 会把它剥离并写页面属性 comments=1
        String text = withoutCategories
                .replace("__NOTOC__", "")
                .replace("__TOC__", "")
                .replace("__FORCETOC__", "")
                .replace("__NOEDITSECTION__", "")
                .replace("__NOINDEX__", "")
                .replace("__INDEX__", "")
                .replace("__NEWSECTIONLINK__", "")
                .replace("__NONEWSECTIONLINK__", "")
                .replace("__HIDDENCAT__", "");

        List<Heading> headings = new ArrayList<>();
        String html = renderBlocks(text, headings, new LinkedHashMap<>());
        html = restoreTokens(html, "\uE000", preBlocks);
        boolean showToc = !noToc && !headings.isEmpty()
                && (headings.size() >= TOC_MIN_HEADINGS || forceToc);
        html = html.replace(TOC_PLACEHOLDER, showToc ? buildToc(headings) : "");
        // 可信块（微件输出，不经过 HTML 白名单）：先去掉独占段落的 <p> 包裹，再一次性还原
        html = Pattern.compile("<p>(" + Pattern.quote("\uE004") + "\\d+" + Pattern.quote("\uE004")
                        + ")</p>")
                .matcher(html).replaceAll("$1");
        html = restoreTokens(html, "\uE004", trustedBlocks);
        // 嵌套可信块（插件标签里再嵌插件标签，如 Vue 组件）：还原外层后，内层占位符才出现在结果里，
        // 再扫几轮直到没有残留（有界循环，防作者写出的字面量死循环）
        for (int pass = 0; pass < 4 && html.indexOf('\uE004') >= 0; pass++) {
            String restored = restoreTokens(html, "\uE004", trustedBlocks);
            if (restored.equals(html)) {
                break;
            }
            html = restored;
        }
        // 插件 HTML 占位（deferHtml，如 Cite 脚注）：HTML 收尾阶段原样替换
        RenderContextState deferredState = CURRENT.get();
        if (deferredState != null && html.indexOf('\uE009') >= 0) {
            for (int index = 0; index < deferredState.deferredHtmlCount(); index++) {
                String marker = "\uE009" + index + "\uE009";
                if (!html.contains(marker)) {
                    continue;
                }
                String value = "";
                java.util.function.Supplier<String> supplier = deferredState.deferredHtmlAt(index);
                if (supplier != null) {
                    try {
                        value = supplier.get();
                    } catch (RuntimeException ignored) {
                        value = "";
                    }
                }
                html = html.replace(marker, value == null ? "" : value);
            }
        }
        return new RenderResult(html, List.copyOf(categories), finalDisplayTitle,
                List.copyOf(interwikiLinks));
    }

    /**
     * 一次性还原占位符 {@code 标记+序号+标记}（如 {@code \uE0010\uE001}）。
     * 逐号 {@code String.replace} 会在「正文数字恰好夹在两个占位符之间」时跨边界误匹配
     * （例如 {@code \uE00133\uE00130\uE00134\uE001} 里搜到 {@code \uE00130\uE001}），必须单遍正则。
     */
    private static String restoreTokens(String text, String marker, List<String> values) {
        Pattern pattern = Pattern.compile(Pattern.quote(marker) + "(\\d+)" + Pattern.quote(marker));
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            String replacement = index >= 0 && index < values.size() ? values.get(index) : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 目录里的一个标题：HTML 级别（2-6）、锚点 id、展示文本（已转义）。 */
    private record Heading(int level, String id, String text) {
    }

    /** 目录树节点（渲染成嵌套 {@code <ul>}）。 */
    private static final class TocNode {
        private final Heading heading;
        private final String number;
        private final List<TocNode> children = new ArrayList<>();

        private TocNode(Heading heading, String number) {
            this.heading = heading;
            this.number = number;
        }
    }

    /** 生成目录 HTML：竖排编号（1 / 1.1 / 1.1.1）与「隐藏 / 显示」开关；插在第一个标题之前。 */
    private String buildToc(List<Heading> headings) {
        List<TocNode> roots = new ArrayList<>();
        Deque<TocNode> stack = new ArrayDeque<>();
        int[] counters = new int[7];
        for (Heading heading : headings) {
            int depth = Math.max(1, Math.min(5, heading.level() - 1));
            counters[depth]++;
            for (int d = depth + 1; d < counters.length; d++) {
                counters[d] = 0;
            }
            TocNode node = new TocNode(heading, tocNumber(counters, depth));
            while (stack.size() >= depth) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().children.add(node);
            }
            stack.push(node);
        }

        StringBuilder out = new StringBuilder();
        out.append("<div id=\"toc\" class=\"toc\" role=\"navigation\">")
                .append("<div class=\"toctitle\">")
                .append("<span class=\"toctext-title\">")
                .append(HtmlUtils.htmlEscape(message("toc.title"))).append("</span>")
                .append("<span class=\"toctoggle\">[")
                .append("<span class=\"toc-hide-label\">")
                .append(HtmlUtils.htmlEscape(message("toc.hide"))).append("</span>")
                .append("<span class=\"toc-show-label\">")
                .append(HtmlUtils.htmlEscape(message("toc.show"))).append("</span>")
                .append("]</span></div>");
        appendTocNodes(out, roots);
        out.append("</div>");
        return out.toString();
    }

    /** 编号：{@code counters[1..depth]} 去掉前导零，用点连接（h2=1、h3=1.1、h4=1.1.1）。 */
    private static String tocNumber(int[] counters, int depth) {
        StringBuilder number = new StringBuilder();
        boolean started = false;
        for (int d = 1; d <= depth; d++) {
            if (!started && counters[d] == 0) {
                continue;
            }
            started = true;
            if (number.length() > 0) {
                number.append('.');
            }
            number.append(counters[d]);
        }
        return number.length() == 0 ? "1" : number.toString();
    }

    private static void appendTocNodes(StringBuilder out, List<TocNode> nodes) {
        out.append("<ul>");
        for (TocNode node : nodes) {
            Heading heading = node.heading;
            out.append("<li class=\"toclevel-").append(Math.max(1, heading.level() - 1)).append("\">")
                    .append("<a href=\"#").append(HtmlSanitizer.escapeAttribute(heading.id())).append("\">")
                    .append("<span class=\"tocnumber\">").append(node.number).append("</span> ")
                    .append("<span class=\"toctext\">").append(heading.text()).append("</span></a>");
            if (!node.children.isEmpty()) {
                appendTocNodes(out, node.children);
            }
            out.append("</li>");
        }
        out.append("</ul>");
    }

    /** 标题文本 → 唯一锚点 id（空格变下划线、去掉危险字符；重复的加 _2 / _3）。 */
    private static String uniqueAnchor(String plainText, Map<String, Integer> anchorCounts) {
        String base = plainText.replace(' ', '_')
                .replaceAll("[\\[\\]{}|<>#\"'%\\\\]", "")
                .trim();
        if (base.isEmpty()) {
            base = "section";
        }
        int count = anchorCounts.merge(base, 1, Integer::sum);
        return count == 1 ? base : base + "_" + count;
    }

    /**
     * 本地化文案：只用键，**不写中文兜底**（缺键时消息源会显示键本身）。
     * <p>
     * 没有 {@link MessageSource}（单元测试等独立场景）时同样直接返回键。
     */
    private String message(String key) {
        return messageSource == null
                ? key
                : messageSource.getMessage(key, null, null, LocaleContextHolder.getLocale());
    }

    /** 模板展开：{{...}} / {{{...}}} / 解析器函数 / 魔术字。 */
    private String expand(String text, Map<String, String> frame, String pageTitle, int depth,
                          Set<String> stack, List<String> trusted) {
        if (depth > properties.getMaxTemplateDepth()) {
            return error("wikitext.tooDeep");
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf("{{", i);
            int nowiki = text.indexOf("<nowiki", i);
            if (nowiki >= 0 && (open < 0 || nowiki < open)) {
                int end = text.indexOf("</nowiki>", nowiki);
                if (end < 0) {
                    out.append(text.substring(i));
                    break;
                }
                out.append(text, i, end + "</nowiki>".length());
                i = end + "</nowiki>".length();
                continue;
            }
            if (open < 0) {
                out.append(text.substring(i));
                break;
            }
            out.append(text, i, open);
            if (text.startsWith("{{{", open)) {
                int close = text.indexOf("}}}", open + 3);
                if (close < 0) {
                    out.append(text.substring(open));
                    break;
                }
                out.append(resolveParameter(text.substring(open + 3, close), frame));
                i = close + 3;
            } else {
                int close = findExpressionEnd(text, open + 2);
                if (close < 0) {
                    out.append(text.substring(open));
                    break;
                }
                out.append(expandExpression(text.substring(open + 2, close), frame, pageTitle, depth, stack, trusted));
                i = close + 2;
            }
        }
        return out.toString();
    }

    private String resolveParameter(String expression, Map<String, String> frame) {
        int pipe = expression.indexOf('|');
        String name = (pipe < 0 ? expression : expression.substring(0, pipe)).trim();
        String defaultValue = pipe < 0 ? null : expression.substring(pipe + 1);
        String value = frame == null ? null : frame.get(name);
        if (value != null) {
            return value;
        }
        if (defaultValue != null) {
            return defaultValue;
        }
        return "{{{" + expression + "}}}";
    }

    private String expandExpression(String expression, Map<String, String> frame,
                                    String pageTitle, int depth, Set<String> stack, List<String> trusted) {
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String colonMagic = colonMagicWord(trimmed, frame, pageTitle, depth, stack, trusted);
        if (colonMagic != null) {
            return colonMagic;
        }
        String magic = magicWord(trimmed, pageTitle);
        if (magic != null) {
            return magic;
        }
        if (trimmed.startsWith("#")) {
            return parserFunction(trimmed, frame, pageTitle, depth, stack, trusted);
        }
        // 不带 # 的插件解析器函数（如页面横幅的 {{PAGEBANNER:文件名|caption=说明}}）：
        // 名字（冒号前）命中插件注册的函数时交给插件，否则仍按模板解析
        String colonFunction = pluginColonFunction(trimmed, frame, pageTitle, depth, stack, trusted);
        if (colonFunction != null) {
            return colonFunction;
        }

        List<String> parts = splitTopLevel(expression, '|');
        String name = parts.get(0).trim();
        Map<String, String> arguments = new LinkedHashMap<>();
        int position = 1;
        for (int i = 1; i < parts.size(); i++) {
            String part = parts.get(i);
            int equals = indexOfTopLevel(part, '=');
            if (equals > 0) {
                String key = part.substring(0, equals).trim();
                if (key.matches("[\\w\\u4e00-\\u9fa5-]+")) {
                    arguments.put(key, expand(part.substring(equals + 1).trim(), frame, pageTitle, depth, stack, trusted));
                    continue;
                }
            }
            arguments.put(String.valueOf(position++), expand(part.trim(), frame, pageTitle, depth, stack, trusted));
        }

        TemplateSource source = findTemplate(name);
        if (source == null) {
            String linkTitle = name.indexOf(':') >= 0 ? name : "模板:" + name;
            return "<a class=\"new\" href=\"" + WikiUrls.pagePrefix() + HtmlSanitizer.escapeAttribute(linkTitle)
                    + "\">" + linkTitle + "</a>";
        }
        if (!stack.add(source.title())) {
            return error("wikitext.loop", source.title());
        }
        String result = expand(prepareTransclusion(source.content()), arguments, pageTitle, depth + 1, stack, trusted);
        stack.remove(source.title());
        return result;
    }

    /** 无 {@code #} 的 MediaWiki 冒号魔术字（lc / fullurl / ns / formatnum / padleft …）。 */
    private String colonMagicWord(String expression, Map<String, String> frame, String pageTitle,
                                  int depth, Set<String> stack, List<String> trusted) {
        int colon = expression.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String name = expression.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String argument = expression.substring(colon + 1);
        List<String> parts = splitTopLevel(argument, '|');
        String first = parts.isEmpty() ? "" : expand(parts.get(0), frame, pageTitle, depth, stack, trusted);
        String second = parts.size() > 1 ? expand(parts.get(1), frame, pageTitle, depth, stack, trusted) : "";
        return switch (name) {
            case "lc" -> first.toLowerCase(Locale.ROOT);
            case "uc" -> first.toUpperCase(Locale.ROOT);
            case "lcfirst" -> first.isEmpty() ? first
                    : first.substring(0, 1).toLowerCase(Locale.ROOT) + first.substring(1);
            case "ucfirst" -> first.isEmpty() ? first
                    : first.substring(0, 1).toUpperCase(Locale.ROOT) + first.substring(1);
            case "urlencode" -> java.net.URLEncoder.encode(first, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            case "anchorencode" -> java.net.URLEncoder.encode(first, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "_");
            case "fullurl", "canonicalurl", "localurl" -> {
                String path = WikiUrls.pagePath(first.trim());
                if (!second.isBlank()) {
                    path = path + (path.contains("?") ? "&" : "?") + second;
                }
                yield path;
            }
            case "ns", "nse" -> {
                int id;
                try {
                    id = Integer.parseInt(first.trim());
                } catch (NumberFormatException exception) {
                    id = switch (first.trim().toLowerCase(Locale.ROOT)) {
                        case "template", "模板" -> 10;
                        case "module", "模块" -> 828;
                        case "file", "image", "文件" -> 6;
                        case "category", "分类" -> 14;
                        case "help", "帮助" -> 12;
                        case "user", "用户" -> 2;
                        case "talk", "讨论" -> 1;
                        case "wikikernel" -> 4;
                        default -> ActionApiNamespaces.idOf(first.trim() + ":X");
                    };
                }
                String ns = ActionApiNamespaces.nameOf(id);
                yield "nse".equals(name)
                        ? java.net.URLEncoder.encode(ns, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20")
                        : ns;
            }
            case "formatnum" -> {
                try {
                    java.text.NumberFormat format = java.text.NumberFormat.getNumberInstance(Locale.CHINA);
                    yield format.format(Double.parseDouble(first.trim()));
                } catch (NumberFormatException exception) {
                    yield first;
                }
            }
            case "padleft", "padright" -> {
                int length;
                try {
                    length = Integer.parseInt(second.trim());
                } catch (NumberFormatException exception) {
                    yield first;
                }
                String fill = parts.size() > 2 ? expand(parts.get(2), frame, pageTitle, depth, stack, trusted) : "0";
                if (fill.isEmpty()) {
                    fill = "0";
                }
                int count = first.codePointCount(0, first.length());
                if (count >= length) {
                    yield first;
                }
                String padding = fill.repeat(length - count);
                yield "padleft".equals(name) ? padding + first : first + padding;
            }
            case "plural" -> {
                if (parts.size() <= 1) {
                    yield "";
                }
                String number = first.trim();
                String one = parts.size() > 1 ? expand(parts.get(1), frame, pageTitle, depth, stack, trusted) : "";
                String many = parts.size() > 2 ? expand(parts.get(2), frame, pageTitle, depth, stack, trusted) : one;
                yield "1".equals(number) || "1.0".equals(number) ? one : many;
            }
            case "grammar" -> second;
            case "gender" -> {
                if (parts.size() > 1) {
                    String unknown = expand(parts.get(parts.size() - 1), frame, pageTitle, depth, stack, trusted);
                    yield unknown;
                }
                yield first;
            }
            case "int" -> {
                if (messageSource == null) {
                    yield first;
                }
                yield messageSource.getMessage(first, null, first, LocaleContextHolder.getLocale());
            }
            case "subst", "safesubst" -> first;
            default -> null;
        };
    }

    private String magicWord(String expression, String pageTitle) {
        if ("!".equals(expression)) {
            return "|";
        }
        String upper = expression.toUpperCase(Locale.ROOT);
        String title = pageTitle == null ? "" : pageTitle;
        LocalDateTime now = LocalDateTime.now();
        String builtin = switch (upper) {
            // 页面名家族（MediaWiki 语义）
            case "PAGENAME" -> shortPageName(title);
            case "FULLPAGENAME" -> title;
            case "SUBPAGENAME" -> subpageName(title);
            case "BASEPAGENAME" -> basePageName(title);
            case "ROOTPAGENAME" -> shortPageName(rootPageName(title));
            case "NAMESPACE" -> namespaceOf(title);
            case "NAMESPACEE" -> encodeTitle(namespaceOf(title));
            case "PAGENAMEE" -> encodeTitle(shortPageName(title));
            case "FULLPAGENAMEE" -> encodeTitle(title);
            // 时间家族（服务器时间；只读展示用）
            case "CURRENTYEAR" -> String.format("%04d", now.getYear());
            case "CURRENTMONTH" -> String.format("%02d", now.getMonthValue());
            case "CURRENTMONTH1" -> String.valueOf(now.getMonthValue());
            case "CURRENTMONTHNAME" -> now.getMonth().getDisplayName(
                    java.time.format.TextStyle.FULL, LocaleContextHolder.getLocale());
            case "CURRENTDAY" -> String.format("%02d", now.getDayOfMonth());
            case "CURRENTDAY2" -> String.valueOf(now.getDayOfMonth());
            case "CURRENTDOW" -> String.valueOf(now.getDayOfWeek().getValue() % 7);
            case "CURRENTTIME" -> String.format("%02d:%02d", now.getHour(), now.getMinute());
            case "CURRENTHOUR" -> String.format("%02d", now.getHour());
            case "CURRENTMINUTE" -> String.format("%02d", now.getMinute());
            case "CURRENTWEEK" -> String.valueOf(
                    now.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()));
            case "CURRENTTIMESTAMP" -> java.time.format.DateTimeFormatter
                    .ofPattern("yyyyMMddHHmmss").format(now);
            case "CURRENTVERSION" -> WikiKernelInfo.VERSION;
            case "REVISIONTIMESTAMP" -> revisionTimestamp(title);
            case "REVISIONUSER" -> revisionUser(title);
            case "SUBJECTPAGENAME" -> subjectPageName(title);
            case "TALKSPACE" -> talkSpaceName(title);
            case "NAMESPACENUMBER", "NSPN" -> String.valueOf(ActionApiNamespaces.idOf(title));
            // 统计类魔术字（wikiStatsService 缺失时渲染为空）
            case "NUMBEROFPAGES" -> stats(0);
            case "NUMBEROFARTICLES" -> stats(1);
            case "NUMBEROFEDITS" -> stats(2);
            case "NUMBEROFFILES" -> stats(3);
            case "NUMBEROFUSERS" -> stats(4);
            case "NUMBEROFADMINS" -> stats(5);
            default -> null;
        };
        if (builtin != null) {
            return builtin;
        }
        // 插件魔术字（如 HitCounters 的 {{NUMBEROFVIEWS}} / {{NUMBEROFPAGEVIEWS}}）
        RenderContextState state = CURRENT.get();
        if (state != null) {
            MagicWord word = state.magicWord(upper);
            if (word != null) {
                String value = word.value(state);
                return value == null ? "" : value;
            }
        }
        return null;
    }

    /** {{REVISIONTIMESTAMP}}：当前页面最新修订时间（14 位）。 */
    private String revisionTimestamp(String pageTitle) {
        if (wikiPageService == null || pageTitle == null || pageTitle.isBlank()) {
            return "";
        }
        return wikiPageService.getVisibleByTitle(pageTitle)
                .flatMap(page -> wikiPageService.getLatestRevision(page.getId()))
                .map(revision -> revision.getCreateAt() == null ? ""
                        : java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                        .format(revision.getCreateAt()))
                .orElse("");
    }

    /** {{REVISIONUSER}}：当前页面最新修订的作者名。 */
    private String revisionUser(String pageTitle) {
        if (wikiPageService == null || pageTitle == null || pageTitle.isBlank()) {
            return "";
        }
        WikiRevision revision = wikiPageService.getVisibleByTitle(pageTitle)
                .flatMap(page -> wikiPageService.getLatestRevision(page.getId()))
                .orElse(null);
        if (revision == null) {
            return "";
        }
        if (revision.getUserId() != null && userService != null) {
            return userService.usernames(List.of(revision.getUserId()))
                    .getOrDefault(revision.getUserId(), "");
        }
        return revision.getUserText() == null ? "" : revision.getUserText();
    }

    /** 主题页标题（讨论页 → 对应主题页；普通页原样）。 */
    private static String subjectPageName(String pageTitle) {
        String value = pageTitle == null ? "" : pageTitle;
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
        return value;
    }

    /** {{TALKSPACE}}：当前页面对应讨论命名空间的名字。 */
    private static String talkSpaceName(String pageTitle) {
        String value = pageTitle == null ? "" : pageTitle;
        String[][] pairs = {
                {"模板:", "模板讨论"}, {"模块:", "模块讨论"}, {"用户:", "用户讨论"},
                {"帮助:", "帮助讨论"}, {"文件:", "文件讨论"}, {"分类:", "分类讨论"},
                {"WikiKernel:", "WikiKernel讨论"}, {"MediaWiki:", "MediaWiki讨论"},
                {"组件:", "组件讨论"}, {"小部件:", "小部件讨论"}, {"图表:", "图表讨论"}
        };
        for (String[] pair : pairs) {
            if (value.startsWith(pair[0])) {
                return pair[1];
            }
        }
        return "讨论";
    }

    /** {{#time:格式|时间戳}}：支持模块/模板常用的 MediaWiki 日期格式符。 */
    private static String formatTime(String format, String timestamp) {
        String pattern = format == null ? "" : format;
        String raw = timestamp == null ? "" : timestamp.trim();
        java.time.LocalDateTime time;
        if (raw.isEmpty()) {
            time = java.time.LocalDateTime.now();
        } else if (raw.matches("\\d{10}")) {
            time = java.time.Instant.ofEpochSecond(Long.parseLong(raw))
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        } else if (raw.matches("\\d{14}")) {
            time = java.time.LocalDateTime.parse(raw,
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        } else if (raw.matches("\\d{8}")) {
            time = java.time.LocalDate.parse(raw,
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")).atStartOfDay();
        } else {
            try {
                time = java.time.LocalDateTime.parse(raw.replace(' ', 'T'));
            } catch (RuntimeException exception) {
                time = java.time.LocalDateTime.now();
            }
        }
        java.time.Instant instant = time.atZone(java.time.ZoneId.systemDefault()).toInstant();
        java.util.Locale locale = LocaleContextHolder.getLocale();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char token = pattern.charAt(i);
            switch (token) {
                case 'Y' -> out.append(String.format("%04d", time.getYear()));
                case 'y' -> out.append(String.format("%02d", time.getYear() % 100));
                case 'n' -> out.append(time.getMonthValue());
                case 'm' -> out.append(String.format("%02d", time.getMonthValue()));
                case 'j' -> out.append(time.getDayOfMonth());
                case 'd' -> out.append(String.format("%02d", time.getDayOfMonth()));
                case 'H' -> out.append(String.format("%02d", time.getHour()));
                case 'G' -> out.append(time.getHour());
                case 'i' -> out.append(String.format("%02d", time.getMinute()));
                case 's' -> out.append(String.format("%02d", time.getSecond()));
                case 'U' -> out.append(instant.getEpochSecond());
                case 'F' -> out.append(time.getMonth().getDisplayName(
                        java.time.format.TextStyle.FULL, locale));
                case 'M' -> out.append(time.getMonth().getDisplayName(
                        java.time.format.TextStyle.SHORT, locale));
                case 'l' -> out.append(time.getDayOfWeek().getDisplayName(
                        java.time.format.TextStyle.FULL, locale));
                case 'D' -> out.append(time.getDayOfWeek().getDisplayName(
                        java.time.format.TextStyle.SHORT, locale));
                case 'T' -> out.append(java.time.ZoneId.systemDefault().getId());
                case '\\' -> {
                    if (i + 1 < pattern.length()) {
                        out.append(pattern.charAt(++i));
                    }
                }
                default -> out.append(token);
            }
        }
        return out.toString();
    }

    /** {{NUMBEROF*}}：由 {@link WikiStatsService} 提供（带短缓存），无服务时返回空串。 */
    private String stats(int index) {        if (wikiStatsService == null) {
            return "";
        }
        long[] values = wikiStatsService.values();
        return index >= 0 && index < values.length ? String.valueOf(values[index]) : "";
    }

    /**
     * 不带 {@code #} 的插件解析器函数（{@code {{PAGEBANNER:文件名|caption=说明}}} 这类 MediaWiki 写法，
     * 对应 {@code setFunctionHook} 注册的解析器函数 / 变量）：名字（第一个 {@code :} 之前）命中插件注册的
     * {@link ParserFunction} 时按 {@code {{#名字:参数}}} 调用；没注册时返回 {@code null}（仍按模板解析，
     * 插件未加载时正文里的指令保持原样）。
     */
    private String pluginColonFunction(String expression, Map<String, String> frame, String pageTitle,
                                       int depth, Set<String> stack, List<String> trusted) {
        int colon = expression.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String name = expression.substring(0, colon).trim();
        if (name.isEmpty() || name.indexOf('|') >= 0) {
            return null;
        }
        RenderContextState state = CURRENT.get();
        if (state == null || state.parserFunction(name) == null) {
            return null;
        }
        return parserFunction("#" + expression, frame, pageTitle, depth, stack, trusted);
    }

    private String parserFunction(String expression, Map<String, String> frame,
                                  String pageTitle, int depth, Set<String> stack, List<String> trusted) {
        int colon = expression.indexOf(':');
        String name = (colon < 0 ? expression.substring(1) : expression.substring(1, colon))
                .trim().toLowerCase(Locale.ROOT);
        String rest = colon < 0 ? "" : expression.substring(colon + 1);
        List<String> args = splitTopLevel(rest, '|');
        switch (name) {
            case "if" -> {
                String condition = args.isEmpty() ? "" : args.get(0).trim();
                String thenValue = args.size() > 1 ? args.get(1) : "";
                String elseValue = args.size() > 2 ? args.get(2) : "";
                return expand(condition.isEmpty() ? elseValue : thenValue, frame, pageTitle, depth, stack, trusted);
            }
            case "ifeq" -> {
                String left = args.isEmpty() ? "" : args.get(0).trim();
                String right = args.size() > 1 ? args.get(1).trim() : "";
                String thenValue = args.size() > 2 ? args.get(2) : "";
                String elseValue = args.size() > 3 ? args.get(3) : "";
                return expand(left.equals(right) ? thenValue : elseValue, frame, pageTitle, depth, stack, trusted);
            }
            case "switch" -> {
                String value = args.isEmpty() ? "" : args.get(0).trim();
                String defaultValue = "";
                for (int i = 1; i < args.size(); i++) {
                    int equals = indexOfTopLevel(args.get(i), '=');
                    if (equals < 0) {
                        continue;
                    }
                    String key = args.get(i).substring(0, equals).trim();
                    String result = args.get(i).substring(equals + 1);
                    if ("#default".equalsIgnoreCase(key)) {
                        defaultValue = result;
                    } else if (key.equals(value)) {
                        return expand(result, frame, pageTitle, depth, stack, trusted);
                    }
                }
                return expand(defaultValue, frame, pageTitle, depth, stack, trusted);
            }
            case "ifexist" -> {
                String title = args.isEmpty() ? "" : expand(args.get(0), frame, pageTitle, depth, stack, trusted).trim();
                boolean exists = this.pageExistenceService != null && this.pageExistenceService.exists(title);
                String thenValue = args.size() > 1 ? args.get(1) : "";
                String elseValue = args.size() > 2 ? args.get(2) : "";
                return expand(exists ? thenValue : elseValue, frame, pageTitle, depth, stack, trusted);
            }
            case "iferror" -> {
                String tested = args.isEmpty() ? ""
                        : expand(args.get(0), frame, pageTitle, depth, stack, trusted);
                boolean hasError = tested.contains("wiki-error") || tested.contains("脚本错误")
                        || tested.contains("Script error");
                if (hasError) {
                    String thenValue = args.size() > 1 ? args.get(1) : "";
                    return expand(thenValue, frame, pageTitle, depth, stack, trusted);
                }
                if (args.size() > 2) {
                    return expand(args.get(2), frame, pageTitle, depth, stack, trusted);
                }
                return tested;
            }
            case "expr" -> {
                String exprText = args.isEmpty() ? ""
                        : expand(args.get(0), frame, pageTitle, depth, stack, trusted);
                return LuaModuleService.evaluateExpression(exprText);
            }
            case "time" -> {
                String format = args.isEmpty() ? "" : expand(args.get(0), frame, pageTitle, depth, stack, trusted);
                String timestamp = args.size() > 1
                        ? expand(args.get(1), frame, pageTitle, depth, stack, trusted) : "";
                return formatTime(format, timestamp);
            }
            case "special" -> {
                String special = args.isEmpty() ? rest : args.get(0);
                return WikiUrls.pagePath("特殊:" + (special == null ? "" : special.trim()));
            }
            case "css" -> {
                // 内容页的 #CSS 目前不作为页面级样式注入（避免任意 CSS 注入）；不显示原始代码。
                return "";
            }
            case "invoke" -> {
                return invokeLua(args, frame, pageTitle, depth, stack, trusted);
            }
            default -> {
                // 插件解析器函数（如 Variables 的 #var / WikiSEO 的 #seo）
                RenderContextState state = CURRENT.get();
                ParserFunction function = state == null ? null : state.parserFunction(name);
                if (function != null) {
                    return function.invoke(new PluginFunctionCall(state, args, frame, pageTitle, depth, stack, trusted));
                }
                return "{{" + expression + "}}";
            }
        }
    }

    // ==================== 插件扩展点支持 ====================

    /** {@link ParserFunctionCall} 的 core 实现：参数是顶层 {@code |} 拆分后的原文。 */
    private final class PluginFunctionCall implements ParserFunctionCall {

        private final RenderContextState state;
        private final List<String> args;
        private final Map<String, String> frame;
        private final String pageTitle;
        private final int depth;
        private final Set<String> stack;
        private final List<String> trusted;

        private PluginFunctionCall(RenderContextState state, List<String> args, Map<String, String> frame,
                                   String pageTitle, int depth, Set<String> stack, List<String> trusted) {
            this.state = state;
            this.args = args;
            this.frame = frame;
            this.pageTitle = pageTitle;
            this.depth = depth;
            this.stack = stack;
            this.trusted = trusted;
        }

        @Override
        public WikiRenderContext context() {
            return state;
        }

        @Override
        public List<String> args() {
            return List.copyOf(args);
        }

        @Override
        public String expand(String wikitext) {
            return WikitextRenderer.this.expand(wikitext == null ? "" : wikitext,
                    frame, pageTitle, depth, stack, trusted);
        }

        @Override
        public String trustedHtml(String html) {
            // 可信块：渲染收尾阶段按 \uE004 序号原样还原（与 <poem> / 插件标签同一机制）
            trusted.add(html == null ? "" : html);
            return "\uE004" + (trusted.size() - 1) + "\uE004";
        }
    }

    /** 替换插件延迟占位（{@code \uE008序号\uE008}）：解析结束后求值，结果按 wikitext 展开。 */
    private String resolveDeferred(String text, List<String> trusted) {
        RenderContextState state = CURRENT.get();
        if (state == null || text == null || text.indexOf('\uE008') < 0) {
            return text;
        }
        for (int i = 0; i < state.deferredCount(); i++) {
            String marker = "\uE008" + i + "\uE008";
            if (!text.contains(marker)) {
                continue;
            }
            String value = "";
            Supplier<String> resolver = state.deferredAt(i);
            if (resolver != null) {
                try {
                    value = resolver.get();
                } catch (RuntimeException ignored) {
                    value = "";
                }
            }
            String resolved = expand(value == null ? "" : value, null, state.pageTitle(), 0,
                    new LinkedHashSet<>(), trusted);
            text = text.replace(marker, resolved);
        }
        return text;
    }

    /** 插件行为开关（{@code __NAME__}）：命中后剥离并调用插件（常写页面属性，如 {@code notitle}）。 */
    private String applyPluginBehaviorSwitches(String text) {
        RenderContextState state = CURRENT.get();
        if (state == null || text == null || text.indexOf("__") < 0) {
            return text;
        }
        Matcher matcher = Pattern.compile("__([A-Za-z0-9\\u4e00-\\u9fa5]+)__").matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (matcher.find()) {
            BehaviorSwitch behavior = state.behaviorSwitch(matcher.group(1));
            if (behavior == null) {
                continue;
            }
            out.append(text, last, matcher.start());
            last = matcher.end();
            changed = true;
            try {
                behavior.apply(state);
            } catch (RuntimeException ignored) {
                // 插件异常不影响页面渲染
            }
        }
        if (!changed) {
            return text;
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** 插件标签（{@code expandBody = true}，如 {@code <seo>}）：正文展开后提取为可信块。 */
    private String extractPluginTags(String text, List<String> trusted) {
        RenderContextState state = CURRENT.get();
        if (state == null || text == null || text.indexOf('<') < 0) {
            return text;
        }
        for (ParserTag tag : state.expandableTags()) {
            for (String name : tag.names()) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                Pattern pattern = tagPattern(name);
                Matcher matcher = pattern.matcher(text);
                StringBuilder out = new StringBuilder();
                int last = 0;
                boolean found = false;
                while (matcher.find()) {
                    found = true;
                    out.append(text, last, matcher.start());
                    String html = renderPluginTag(tag, state, name, matcher.group(1), matcher.group(2), trusted);
                    trusted.add(html == null ? "" : html);
                    out.append('\uE004').append(trusted.size() - 1).append('\uE004');
                    last = matcher.end();
                }
                if (found) {
                    out.append(text, last, text.length());
                    text = out.toString();
                }
            }
        }
        return text;
    }

    /** 插件标签（{@code expandBody = false}，如 {@code <templatedata>}）：展开前整段保护（原文不参与模板展开）。 */
    private String protectPluginRawTags(String input, List<String> stash) {
        RenderContextState state = CURRENT.get();
        if (state == null || input == null || input.indexOf('<') < 0) {
            return input;
        }
        String result = input;
        for (ParserTag tag : state.rawTags()) {
            for (String name : tag.names()) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                Matcher matcher = tagPattern(name).matcher(result);
                StringBuilder out = new StringBuilder();
                int last = 0;
                boolean found = false;
                while (matcher.find()) {
                    found = true;
                    out.append(result, last, matcher.start());
                    String html = renderPluginTag(tag, state, name, matcher.group(1), matcher.group(2), List.of());
                    stash.add(html == null ? "" : html);
                    out.append('\uE000').append(stash.size() - 1).append('\uE000');
                    last = matcher.end();
                }
                if (found) {
                    out.append(result, last, result.length());
                    result = out.toString();
                }
            }
        }
        return result;
    }

    private static Pattern tagPattern(String name) {
        String quoted = Pattern.quote(name);
        // 支持成对标签（group 2 = 内容）与自闭合标签（如 <flash-player … />、<seo … />，内容为空）
        return Pattern.compile("(?si)<" + quoted + "\\b([^>]*?)(?:/>|>(.*?)</" + quoted + "\\s*>)");
    }

    private String renderPluginTag(ParserTag tag, RenderContextState state, String tagName, String attributeText,
                                   String body, List<String> trusted) {
        Map<String, String> attributes = parseAttributes(attributeText == null ? "" : attributeText);
        String bodyText = body == null ? "" : body;
        ParserTagCall call = new ParserTagCall() {
            @Override
            public WikiRenderContext context() {
                return state;
            }

            @Override
            public String tag() {
                // 匹配到的那个名字（多名字标签如 Vue 组件：name() 只是代表名，实际用的是别名）
                return tagName == null || tagName.isBlank() ? tag.name() : tagName;
            }

            @Override
            public Map<String, String> attributes() {
                return attributes;
            }

            @Override
            public String body() {
                return bodyText;
            }

            @Override
            public String expand(String wikitext) {
                return WikitextRenderer.this.expand(wikitext == null ? "" : wikitext, null,
                        state.pageTitle(), 0, new LinkedHashSet<>(), trusted);
            }
        };
        try {
            String html = tag.render(call);
            return html == null ? "" : html;
        } catch (RuntimeException e) {
            // 标签渲染失败：记日志（含堆栈）并显示一行错误，避免只看到「渲染失败」无从排查
            log.warn("扩展标签 <{}> 渲染失败（{}）", tagName, tag.getClass().getName(), e);
            return error("wikitext.pluginTagFailed", tagName);
        }
    }


    /**
     * {@code {{#invoke:模块|函数|…}}}：运行 Lua 模块并把返回文本继续按 Wikitext 解析（仿 Scribunto）。
     * <ul>
     *     <li>位置参数不裁剪空白、命名参数裁剪（与 Scribunto 一致）；</li>
     *     <li>调用循环（模块 A 调 B、B 又调 A）由模板栈拦截；</li>
     *     <li>模块不可用 / 出错时返回本地化错误提示，不影响页面其余内容。</li>
     * </ul>
     */
    private String invokeLua(List<String> args, Map<String, String> frame, String pageTitle,
                             int depth, Set<String> stack, List<String> trusted) {
        if (this.luaModuleService == null) {
            return error("lua.error.disabled");
        }
        if (args.isEmpty() || args.get(0).trim().isEmpty()) {
            return error("lua.error.noModule");
        }
        String moduleName = expand(args.get(0).trim(), frame, pageTitle, depth, stack, trusted).trim();
        String functionName = args.size() > 1
                ? expand(args.get(1).trim(), frame, pageTitle, depth, stack, trusted).trim()
                : "";
        if (functionName.isEmpty()) {
            return error("lua.error.noFunction");
        }
        Map<String, String> positional = new LinkedHashMap<>();
        Map<String, String> named = new LinkedHashMap<>();
        for (int i = 2; i < args.size(); i++) {
            String part = args.get(i);
            int equals = indexOfTopLevel(part, '=');
            if (equals > 0) {
                String key = expand(part.substring(0, equals).trim(), frame, pageTitle,
                        depth, stack, trusted).trim();
                String value = expand(part.substring(equals + 1), frame, pageTitle,
                        depth, stack, trusted).trim();
                named.put(key, value);
            } else {
                positional.put(String.valueOf(positional.size() + 1),
                        expand(part, frame, pageTitle, depth, stack, trusted));
            }
        }
        String loopKey = "#invoke:" + moduleName + "#" + functionName;
        if (!stack.add(loopKey)) {
            return error("lua.error.loop", moduleName);
        }
        try {
            String parentTitle = currentTemplateTitle(stack);
            LuaModuleService.FrameExpander expander = new LuaModuleService.FrameExpander() {
                @Override
                public String preprocess(String text) {
                    return expand(text == null ? "" : text, frame, pageTitle, depth + 1, stack, trusted);
                }

                @Override
                public String expandTemplate(String name, Map<String, String> args) {
                    return expand("{{" + name + frameArguments(args) + "}}",
                            frame, pageTitle, depth + 1, stack, trusted);
                }

                @Override
                public String callParserFunction(String name, Map<String, String> args) {
                    String argumentText = frameArguments(args);
                    if (argumentText.startsWith("|")) {
                        argumentText = argumentText.substring(1);
                    }
                    return expand("{{#" + name + ":" + argumentText + "}}",
                            frame, pageTitle, depth + 1, stack, trusted);
                }
            };
            LuaModuleService.FrameExpander previous = LuaModuleService.swapFrameExpander(expander);
            try {
                LuaModuleService.Result result = this.luaModuleService.invoke(
                        moduleName, functionName, positional, named, frame, parentTitle, pageTitle);
                if (!result.ok()) {
                    return plainError(result.text());
                }
                // 返回文本按 Wikitext 继续解析（模板 / 魔术字 / 内链等），与 Scribunto 行为一致
                return expand(result.text(), frame, pageTitle, depth + 1, stack, trusted);
            } finally {
                LuaModuleService.swapFrameExpander(previous);
            }
        } finally {
            stack.remove(loopKey);
        }
    }

    /** frame 参数表 → wikitext 的 {@code |k=v} / {@code |value} 片段。 */
    private static String frameArguments(Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : args.entrySet()) {
            if (entry.getKey().matches("\\d+")) {
                out.append('|').append(entry.getValue());
            } else {
                out.append('|').append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        return out.toString();
    }

    /** 已本地化的纯文本错误（转义后包一层错误样式；与 {@link #error} 一致但不查消息源）。 */
    private static String plainError(String text) {
        return "<span class=\"wiki-error\">" + HtmlUtils.htmlEscape(text == null ? "" : text) + "</span>";
    }

    /** 当前正在展开的模板标题（栈里最后一个模板项）；直接写在页面里的 #invoke 没有模板标题。 */
    private static String currentTemplateTitle(Set<String> stack) {
        String current = null;
        for (String entry : stack) {
            if (entry.startsWith("模板:") || entry.startsWith("Template:")
                    || entry.startsWith("模板讨论:") || entry.startsWith("Template talk:")) {
                current = entry;
            }
        }
        return current;
    }

    private TemplateSource findTemplate(String name) {
        // 名字里已带命名空间（如 模板:X / Template:X / 帮助:X）就按完整标题查
        if (name.indexOf(':') >= 0) {
            Optional<String> direct = templateResolver.resolve(name);
            return direct.map(content -> new TemplateSource(name, content)).orElse(null);
        }
        for (String prefix : properties.getTemplatePrefixes()) {
            String title = prefix + name;
            Optional<String> content = templateResolver.resolve(title);
            if (content.isPresent()) {
                return new TemplateSource(title, content.get());
            }
        }
        return null;
    }

    // ==================== 模板包含标签 ====================

    private String prepareDirect(String content) {
        String result = removeTagPairs(content, "includeonly");
        result = unwrapTagPairs(result, "noinclude");
        return unwrapTagPairs(result, "onlyinclude");
    }

    private String prepareTransclusion(String content) {
        String only = extractOnlyInclude(content);
        if (only != null) {
            return only;
        }
        String result = removeTagPairs(content, "noinclude");
        return unwrapTagPairs(result, "includeonly");
    }

    private String extractOnlyInclude(String content) {
        Matcher matcher = Pattern.compile("<onlyinclude(?:\\s[^>]*)?>(.*?)</onlyinclude>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(content);
        StringBuilder result = new StringBuilder();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            result.append(matcher.group(1));
        }
        return found ? result.toString() : null;
    }

    private String removeTagPairs(String content, String tag) {
        return content.replaceAll("(?is)<" + tag + "(?:\\s[^>]*)?>.*?</" + tag + ">", "");
    }

    private String unwrapTagPairs(String content, String tag) {
        return content.replaceAll("(?is)</?" + tag + "(?:\\s[^>]*)?>", "");
    }

    // ==================== 块级解析 ====================

    private String renderBlocks(String text, List<Heading> headings, Map<String, Integer> anchorCounts) {
        String[] lines = text.split("\n", -1);
        StringBuilder html = new StringBuilder();
        List<String> paragraph = new ArrayList<>();
        Deque<String> listStack = new ArrayDeque<>();
        int i = 0;
        while (i < lines.length) {
            String rawLine = lines[i];
            String line = rawLine.trim();
            if (line.isEmpty()) {
                flushParagraph(html, paragraph);
                closeLists(html, listStack);
                i++;
                continue;
            }
            if (PLACEHOLDER_LINE.matcher(line).matches()) {
                flushParagraph(html, paragraph);
                closeLists(html, listStack);
                html.append(line);
                i++;
                continue;
            }
            if (line.startsWith("{|")) {
                flushParagraph(html, paragraph);
                closeLists(html, listStack);
                TableData table = collectTable(lines, i);
                html.append(renderTable(table));
                i = table.nextIndex();
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(html, paragraph);
                closeLists(html, listStack);
                int level = heading.group(1).length();
                String inner = inline(heading.group(2));
                String plain = stripDisplayTokens(
                        HtmlUtils.htmlUnescape(inner.replaceAll("<[^>]*>", "")).trim());
                String id = uniqueAnchor(plain, anchorCounts);
                headings.add(new Heading(level, id, HtmlUtils.htmlEscape(plain)));
                if (headings.size() == 1) {
                    html.append(TOC_PLACEHOLDER); // 目录插在第一个标题之前（仿 MediaWiki）
                }
                html.append("<h").append(level).append(" id=\"")
                        .append(HtmlSanitizer.escapeAttribute(id)).append("\">")
                        .append(inner).append("</h").append(level).append('>');
                i++;
                continue;
            }
            if (HR.matcher(line).matches()) {
                flushParagraph(html, paragraph);
                closeLists(html, listStack);
                html.append("<hr>");
                i++;
                continue;
            }
            Matcher list = LIST.matcher(line);
            if (list.matches()) {
                flushParagraph(html, paragraph);
                renderListItem(html, listStack, list.group(1), list.group(2));
                i++;
                continue;
            }
            closeLists(html, listStack);
            paragraph.add(line);
            i++;
        }
        flushParagraph(html, paragraph);
        closeLists(html, listStack);
        return html.toString();
    }

    private void flushParagraph(StringBuilder html, List<String> paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        html.append("<p>").append(inline(String.join(" ", paragraph))).append("</p>");
        paragraph.clear();
    }

    private void renderListItem(StringBuilder html, Deque<String> stack, String prefix, String content) {
        List<String> types = new ArrayList<>();
        for (char c : prefix.toCharArray()) {
            types.add(c == '#' ? "ol" : (c == ':' || c == ';') ? "dl" : "ul");
        }
        while (stack.size() > types.size()) {
            html.append("</").append(stack.pop()).append('>');
        }
        for (int level = stack.size(); level < types.size(); level++) {
            String type = types.get(level);
            html.append('<').append(type).append('>');
            stack.push(type);
        }
        String tag = "dl".equals(stack.peek()) ? (prefix.endsWith(";") ? "dt" : "dd") : "li";
        html.append('<').append(tag).append('>').append(inline(content.trim())).append("</").append(tag).append('>');
    }

    private void closeLists(StringBuilder html, Deque<String> stack) {
        while (!stack.isEmpty()) {
            html.append("</").append(stack.pop()).append('>');
        }
    }

    private TableData collectTable(String[] lines, int start) {
        // {| 行上的属性（class / style）——模板里的信息框靠它浮动到右上角
        String attributes = lines[start].trim().substring(2).trim();
        List<String> tableLines = new ArrayList<>();
        int i = start + 1;
        while (i < lines.length && !lines[i].trim().startsWith("|}")) {
            tableLines.add(lines[i]);
            i++;
        }
        return new TableData(attributes, tableLines, i + 1);
    }

    private String renderTable(TableData table) {
        StringBuilder html = new StringBuilder("<table").append(renderTableAttributes(table.attributes())).append('>');
        List<Cell> cells = new ArrayList<>();
        for (String line : table.tableLines()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|+")) {
                html.append("<caption>").append(inline(trimmed.substring(2).trim())).append("</caption>");
            } else if (trimmed.startsWith("|-")) {
                flushRow(html, cells);
            } else if (trimmed.startsWith("!")) {
                for (String cell : trimmed.substring(1).split("!!", -1)) {
                    cells.add(new Cell(true, cell.trim()));
                }
            } else if (trimmed.startsWith("|")) {
                for (String cell : trimmed.substring(1).split("\\|\\|", -1)) {
                    cells.add(new Cell(false, cell.trim()));
                }
            }
        }
        flushRow(html, cells);
        html.append("</table>");
        return html.toString();
    }

    /** 表格属性：默认带上 wikitable，{@code {| class="…" style="…"}} 里的 class 合并、style 过滤后输出。 */
    private String renderTableAttributes(String attributesText) {
        String extraClass = "";
        String style = "";
        if (attributesText != null && !attributesText.isBlank()) {
            Matcher matcher = ATTR.matcher(attributesText);
            while (matcher.find()) {
                String name = matcher.group(1).toLowerCase(Locale.ROOT);
                String value = matcher.group(2) != null ? matcher.group(2)
                        : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
                if (value == null) {
                    continue;
                }
                if ("class".equals(name)) {
                    extraClass = value.trim();
                } else if ("style".equals(name) && HtmlSanitizer.isSafeStyle(value)) {
                    style = value;
                }
            }
        }
        StringBuilder out = new StringBuilder(" class=\"");
        if (extraClass.isEmpty()) {
            out.append("wikitable");
        } else if (extraClass.matches("(?i).*\\bwikitable\\b.*")) {
            out.append(HtmlSanitizer.escapeAttribute(extraClass));
        } else {
            out.append("wikitable ").append(HtmlSanitizer.escapeAttribute(extraClass));
        }
        out.append('"');
        if (!style.isEmpty()) {
            out.append(" style=\"").append(HtmlSanitizer.escapeAttribute(style)).append('"');
        }
        return out.toString();
    }

    private void flushRow(StringBuilder html, List<Cell> cells) {
        if (cells.isEmpty()) {
            return;
        }
        html.append("<tr>");
        for (Cell cell : cells) {
            String body = cell.content();
            String attributes = "";
            int bar = body.indexOf('|');
            if (bar > 0 && body.substring(0, bar).contains("=")) {
                attributes = body.substring(0, bar);
                body = body.substring(bar + 1);
            }
            String tag = cell.header() ? "th" : "td";
            html.append('<').append(tag).append(renderCellAttributes(attributes)).append('>')
                    .append(inline(body.trim())).append("</").append(tag).append('>');
        }
        html.append("</tr>");
        cells.clear();
    }

    private String renderCellAttributes(String attributesText) {
        if (attributesText.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        Matcher matcher = ATTR.matcher(attributesText);
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            if (value == null) {
                continue;
            }
            if ("class".equals(name) || "align".equals(name) || "valign".equals(name)) {
                out.append(' ').append(name).append("=\"").append(HtmlSanitizer.escapeAttribute(value)).append('"');
            } else if (("colspan".equals(name) || "rowspan".equals(name)) && value.matches("\\d{1,2}")) {
                out.append(' ').append(name).append("=\"").append(value).append('"');
            } else if ("style".equals(name) && HtmlSanitizer.isSafeStyle(value)) {
                out.append(" style=\"").append(HtmlSanitizer.escapeAttribute(value)).append('"');
            }
        }
        return out.toString();
    }

    private static String nameAttribute(String attrs) {
        Matcher matcher = NAME_ATTRIBUTE.matcher(attrs);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1) != null ? matcher.group(1)
                : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        return value == null ? null : value.trim();
    }

    /** 去掉标题 / 目录文本里的控件占位符（微件 \uE004、插件 HTML 占位 \uE009，如 Cite 脚注）。 */
    private static String stripDisplayTokens(String text) {
        return text.replaceAll("[\uE004\uE009]\\d+[\uE004\uE009]", "").trim();
    }

    // ==================== 行内解析 ====================

    private String inline(String text) {
        if (text.isEmpty()) {
            return "";
        }
        List<String> tokens = new ArrayList<>();
        String staged = stashTags(text, tokens);
        String escaped = HtmlSanitizer.escapeText(staged);
        escaped = BOTH.matcher(escaped).replaceAll("<b><i>$1</i></b>");
        escaped = BOLD.matcher(escaped).replaceAll("<b>$1</b>");
        escaped = ITALIC.matcher(escaped).replaceAll("<i>$1</i>");
        escaped = renderWikiLinks(escaped);
        escaped = renderExternalLinks(escaped);
        return restoreTokens(escaped, "\uE001", tokens);
    }

    private String stashTags(String text, List<String> tokens) {
        Matcher matcher = TAG.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            String safe = HtmlSanitizer.sanitizeTag(matcher.group());
            tokens.add(safe != null ? safe : HtmlSanitizer.escapeText(matcher.group()));
            out.append('\uE001').append(tokens.size() - 1).append('\uE001');
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    private String renderWikiLinks(String text) {
        Matcher matcher = WIKI_LINK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String target = matcher.group(1).trim();
            String rawLabel = matcher.group(2);
            String label = rawLabel != null ? rawLabel : target;
            if (isFileTarget(target)) {
                // 文件嵌入：[[文件:名称.png|thumb|说明|200px]]
                matcher.appendReplacement(out, Matcher.quoteReplacement(renderFileEmbedding(target, rawLabel)));
                continue;
            }
            // 跨 Wiki 前缀（[[w:页面]]）：语言链接已在收集阶段剥离，剩下的渲染成外部链接
            InterwikiService.Target interwiki = interwikiTarget(target.startsWith(":") ? target.substring(1).trim() : target);
            if (interwiki != null && !interwiki.local()) {
                // [[:w:页面]]（前导冒号）= 强制普通链接：语言链接也照常渲染成外链
                String pageTitle = interwikiPageTitle(target.startsWith(":") ? target.substring(1).trim() : target);
                if (rawLabel == null) {
                    label = target.startsWith(":") ? target.substring(1).trim() : target;
                }
                String url = interwikiUrl(interwiki, pageTitle);
                matcher.appendReplacement(out, Matcher.quoteReplacement(
                        "<a class=\"external mw-interwiki\" href=\"" + HtmlSanitizer.escapeAttribute(url)
                                + "\" target=\"_blank\" rel=\"nofollow\">" + label + "</a>"));
                continue;
            }
            String href;
            // 红链判断的目标（null = 不检查：外链 / 文件嵌入）
            String existenceTarget = null;
            if (target.startsWith(":")) {
                // 前导冒号：强制普通链接（如 [[:分类:X]] / [[:文件:X]]），显示名去掉冒号
                String real = target.substring(1).trim();
                href = WikiUrls.pagePrefix() + HtmlSanitizer.escapeAttribute(real.replace(' ', '_'));
                existenceTarget = real;
                if (rawLabel == null) {
                    label = real;
                }
            } else if (target.matches("(?i)^(https?|ftp)://.*")) {
                href = HtmlSanitizer.escapeAttribute(target);
            } else {
                href = WikiUrls.pagePrefix() + HtmlSanitizer.escapeAttribute(target.replace(' ', '_'));
                existenceTarget = target;
            }
            String cssClass = isMissingPage(existenceTarget) ? " class=\"new\"" : "";
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    "<a" + cssClass + " href=\"" + href + "\">" + label + "</a>"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 跨 Wiki 前缀命中（{@code null} = 不是跨 Wiki 前缀 / 未启用）。 */
    private InterwikiService.Target interwikiTarget(String target) {
        if (interwikiService == null || target == null) {
            return null;
        }
        int colon = target.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        return interwikiService.find(target.substring(0, colon)).orElse(null);
    }

    /** 跨 Wiki 链接里的页面名（{@code w:页面} → {@code 页面}）。 */
    private static String interwikiPageTitle(String target) {
        int colon = target.indexOf(':');
        return colon < 0 ? target : target.substring(colon + 1).trim();
    }

    /** 跨 Wiki 目标 URL：{@code $1} 替换成 URL 编码的页面名（不含 {@code $1} 时追加到末尾）。 */
    public static String interwikiUrl(InterwikiService.Target target, String pageTitle) {
        String encoded = WikiUrls.encodeInterwikiTitle(pageTitle);
        String template = target.urlTemplate() == null ? "" : target.urlTemplate().trim();
        return template.contains("$1") ? template.replace("$1", encoded) : template + encoded;
    }

    /**
     * 跨语言链接（{@code [[en:页面]]}）从正文剥离并收集（仿 MediaWiki 的跨语言链接）：
     * 每个语言前缀只保留第一次出现；{@code [[:en:页面]]}（前导冒号）强制当普通链接，不剥离。
     */
    private String stripLanguageLinks(String text, List<InterwikiService.Link> out) {
        if (interwikiService == null || text.indexOf("[[") < 0) {
            return text;
        }
        Map<String, InterwikiService.Link> collected = new LinkedHashMap<>();
        Matcher matcher = WIKI_LINK.matcher(text);
        StringBuilder cleaned = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String target = matcher.group(1).trim();
            if (target.startsWith(":") || isFileTarget(target)) {
                continue;
            }
            InterwikiService.Target found = interwikiTarget(target);
            if (found == null || !found.languageLink()) {
                continue;
            }
            cleaned.append(text, last, matcher.start());
            last = matcher.end();
            String key = found.prefix().toLowerCase(Locale.ROOT);
            if (!collected.containsKey(key)) {
                String pageTitle = interwikiPageTitle(target);
                String language = found.language() == null ? "" : found.language();
                collected.put(key, new InterwikiService.Link(found.prefix(), pageTitle,
                        interwikiUrl(found, pageTitle), language, interwikiService.languageName(language)));
            }
        }
        if (collected.isEmpty()) {
            return text;
        }
        cleaned.append(text, last, text.length());
        out.addAll(collected.values());
        return cleaned.toString();
    }

    /**
     * 未知页面红链（{@code class="new"}，仿 MediaWiki）：
     * 目标页面不存在（已删除 / 已隐藏视为不存在）时返回 true；特殊页与页内锚点视为存在。
     */
    private boolean isMissingPage(String target) {
        if (target == null || pageExistenceService == null) {
            return false;
        }
        String title = target.trim();
        int hash = title.indexOf('#');
        if (hash >= 0) {
            title = title.substring(0, hash).trim();
        }
        if (title.isEmpty() || title.startsWith("#")) {
            return false; // [[#章节]]：本页锚点
        }
        return !pageExistenceService.exists(title);
    }

    /** 是否是文件引用（{@code 文件:xxx} / {@code File:xxx} / {@code Image:xxx}）。 */
    private static boolean isFileTarget(String target) {
        return FileTitles.isFileTitle(target);
    }

    /**
     * 渲染文件嵌入（在文本已转义之后调用）：
     * 文件不存在 → 红链；存在 → {@code <img>}，支持 thumb/缩略图、宽度 Npx、left/right/center、alt=、link=（空 = 不链接）。
     */
    private String renderFileEmbedding(String target, String rawLabel) {
        int colon = target.indexOf(':');
        String prefix = target.substring(0, colon);
        String fileName = target.substring(colon + 1).trim().replace(' ', '_');
        String display = HtmlSanitizer.escapeText(prefix + ":" + fileName);
        Optional<WikiFile> found = fileService == null ? Optional.empty() : fileService.findByName(fileName);
        if (found.isEmpty()) {
            String url = WikiUrls.pagePath(FileTitles.titleOf(fileName));
            return "<a class=\"new\" href=\"" + HtmlSanitizer.escapeAttribute(url) + "\">" + display + "</a>";
        }

        WikiFile file = found.get();
        boolean thumb = false;
        String align = "";
        int width = -1;
        String alt = display;
        String caption = "";
        String link = null;
        boolean noLink = false;
        Map<String, String> options = new LinkedHashMap<>();
        List<String> rawOptions = rawLabel == null || rawLabel.isBlank()
                ? List.of() : Arrays.asList(rawLabel.split("\\|", -1));
        for (String raw : rawOptions) {
            String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            String lower = part.toLowerCase(Locale.ROOT);
            if ("thumb".equals(lower) || "thumbnail".equals(lower) || "缩略图".equals(part)) {
                thumb = true;
            } else if ("left".equals(lower) || "左".equals(part)) {
                align = "left";
            } else if ("right".equals(lower) || "右".equals(part)) {
                align = "right";
            } else if ("center".equals(lower) || "居中".equals(part)) {
                align = "center";
            } else if (lower.startsWith("alt=")) {
                alt = part.substring(4);
            } else if (lower.startsWith("link=")) {
                String value = part.substring(5).trim();
                link = value;
                noLink = value.isEmpty();
            } else {
                Matcher widthMatcher = FILE_WIDTH.matcher(part);
                if (widthMatcher.matches()) {
                    width = Integer.parseInt(widthMatcher.group(1));
                } else if (NAMED_OPTION.matcher(part).matches()) {
                    // 命名参数（如 [[文件:X.webm|start=10]]）：交给插件渲染器（TimedMediaHandler 风格）
                    int equals = part.indexOf('=');
                    options.put(part.substring(0, equals).trim().toLowerCase(Locale.ROOT),
                            part.substring(equals + 1).trim());
                } else {
                    caption = caption.isEmpty() ? part : caption + " " + part;
                }
            }
        }

        String image = "<img class=\"wiki-media-image\" src=\"" + HtmlSanitizer.escapeAttribute(fileService.urlOf(file))
                + "\" alt=\"" + attributeText(alt) + "\""
                + (width > 0 ? " width=\"" + width + "\"" : "")
                + " data-file=\"" + attributeText(HtmlSanitizer.escapeText("文件:" + file.getName())) + "\""
                + (caption.isEmpty() ? ""
                : " data-caption=\"" + attributeText(HtmlSanitizer.escapeText(caption)) + "\"")
                + " loading=\"lazy\">";
        String mediaKind = fileService.mediaKind(file.getName());
        String content = renderPluginFileEmbed(file, width > 0 ? width : null, caption, display, options);
        if (content == null) {
            if (fileService.isImage(file)) {
                String href;
                if (noLink) {
                    href = null;
                } else if (link != null) {
                    href = link.matches("(?i)^(https?|ftp)://.*")
                            ? HtmlSanitizer.escapeAttribute(link)
                            : WikiUrls.pagePrefix() + HtmlSanitizer.escapeAttribute(link.replace(' ', '_'));
                } else {
                    href = HtmlSanitizer.escapeAttribute(fileService.pageUrlOf(file));
                }
                content = href == null ? image : "<a class=\"image\" href=\"" + href + "\">" + image + "</a>";
            } else {
                // 非图片（或没有插件处理）：图标 + 文件名，链到文件页（仿 MediaWiki 的非媒体文件嵌入）
                content = MediaPlayerHtml.fileBox(fileService.pageUrlOf(file), file.getName(), mediaKind);
            }
        }

        if (thumb) {
            String position = "left".equals(align) ? " wiki-thumb-left"
                    : "center".equals(align) ? " wiki-thumb-center" : " wiki-thumb-right";
            return "<figure class=\"wiki-thumb" + position + "\">" + content
                    + (caption.isEmpty() ? "" : "<figcaption>" + caption + "</figcaption>")
                    + "</figure>";
        }
        if ("center".equals(align)) {
            return "<div class=\"wiki-image-center\">" + content + "</div>";
        }
        if ("left".equals(align) || "right".equals(align)) {
            return "<span class=\"wiki-float-" + align + "\">" + content + "</span>";
        }
        return content;
    }

    /** 已转义文本放进属性：只需再转义引号（escapeText 不处理引号）。 */
    private static String attributeText(String escapedText) {
        return escapedText.replace("\"", "&quot;");
    }

    /**
     * 询问插件渲染器（{@code common.extension.FileEmbedRenderer}，如多媒体预览）该文件的嵌入内容：
     * 第一个返回非空 HTML 的渲染器胜出；没有插件处理（或插件抛异常）时返回 {@code null}，由 core 走默认行为。
     */
    private String renderPluginFileEmbed(WikiFile file, Integer width, String caption, String alt,
                                         Map<String, String> options) {
        RenderContextState state = CURRENT.get();
        if (state == null || fileService == null || state.fileEmbedRenderers().isEmpty()) {
            return null;
        }
        FileEmbedCall call = WikiFileEmbedCall.of(file, fileService, width, caption, alt, options);
        for (FileEmbedRenderer renderer : state.fileEmbedRenderers()) {
            try {
                String html = renderer.render(call);
                if (html != null && !html.isBlank()) {
                    return html;
                }
            } catch (RuntimeException exception) {
                log.warn("插件媒体渲染失败（{}）：{}", renderer.getClass().getName(), exception.getMessage());
            }
        }
        return null;
    }

    private String renderExternalLinks(String text) {
        Matcher matcher = EXTERNAL_LINK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String url = matcher.group(1);
            String label = matcher.group(2) != null && !matcher.group(2).isBlank() ? matcher.group(2) : url;
            if (!HtmlSanitizer.isSafeUrl(url)) {
                continue;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    "<a class=\"external\" href=\"" + HtmlSanitizer.escapeAttribute(url)
                            + "\" target=\"_blank\" rel=\"nofollow\">"
                            + label + "</a>"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // ==================== 预格式化块 ====================

    private String protectPreBlocks(String input, List<String> stash) {
        String result = protect(input, NOWIKI, stash, HtmlSanitizer::escapeText);
        // <markdown>（仿 MediaWiki 的 Markdown 类扩展）：**紧跟 nowiki、早于其它标签**保护，
        // 这样标签内的内容整体按原始文本走 Markdown 解析——里面的 wikitext 语法（[[…]]、{{…}}、{|）
        // 与 <pre> / <syntaxhighlight> 这些标签写法都不会被当成 wikitext 解析（与 <nowiki> 同级）
        result = protectTag(result, MARKDOWN, stash, this::renderMarkdown);
        result = protectSyntax(result, SYNTAX, stash);
        result = protectSyntax(result, SOURCE, stash);
        result = protect(result, PRE, stash,
                content -> "<pre>" + HtmlSanitizer.escapeText(stripLeadingNewline(content)) + "</pre>");
        // <graph>（Graph 扩展）已迁到插件 graph：这里不再内置解析，插件未加载时标签按原文转义
        // 插件标签（expandBody=false，如 <templatedata>）：正文展开前整段保护，渲染结果原样放回
        result = protectPluginRawTags(result, stash);
        return result;
    }

    /**
     * {@code <markdown>} 的渲染：内容交给 {@link MarkdownRenderer}（CommonMark + GFM 表格 / 删除线，
     * 行内 HTML 过 {@link HtmlSanitizer} 白名单），产出 {@code <div class="markdown">…</div>}。
     * <p>
     * 标签属性只认 {@code class}（附加到 {@code markdown} 之后，值经转义）；
     * 其它属性（{@code lang} / {@code style}…）一律忽略，不报错——仿 MediaWiki 的宽容行为。
     * 内容里开标签后紧跟的那个换行与首尾空行按 {@link #stripEdgeBlankLines} 剥掉。
     */
    private String renderMarkdown(String attributes, String content) {
        String extraClass = parseAttributes(attributes).getOrDefault("class", "");
        return "<div class=\"" + ("markdown" + (extraClass.isBlank()
                ? "" : " " + HtmlSanitizer.escapeAttribute(extraClass)))
                + "\">" + markdownRenderer.render(stripEdgeBlankLines(content)) + "</div>";
    }

    /** 解析标签属性（{@code key="value"} / {@code key=value} / {@code key}），键转小写。 */
    private static Map<String, String> parseAttributes(String attributes) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = ATTR.matcher(attributes == null ? "" : attributes);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3)
                    : matcher.group(4) != null ? matcher.group(4) : "";
            if (key != null && !key.isEmpty()) {
                result.put(key.toLowerCase(Locale.ROOT), value.trim());
            }
        }
        return result;
    }

    /**
     * {@code <syntaxhighlight lang="…">} / {@code <source lang="…">}：按语言做服务端语法高亮
     * （{@link SyntaxHighlighter}），未知 / 缺省语言退化为普通转义。
     * <p>
     * 标签内容会先剥掉**首尾空行**：大家习惯写成
     * {@code <syntaxhighlight lang="bash">} 换行后再写第一行代码，
     * 紧跟开标签的那个换行不应该渲染成一行空白（与 MediaWiki 的 syntaxhighlight 行为一致）；
     * 第一行自身的缩进与中间的空行都保留。
     */
    private String protectSyntax(String input, Pattern pattern, List<String> stash) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String language = attribute(matcher.group(1) == null ? "" : matcher.group(1), LANG_ATTRIBUTE);
            out.append(input, last, matcher.start());
            stash.add("<pre class=\"syntax\""
                    + (language.isEmpty() ? ""
                    : " data-lang=\"" + HtmlSanitizer.escapeAttribute(language) + "\"")
                    + "><code>" + SyntaxHighlighter.highlight(stripEdgeBlankLines(matcher.group(2)), language)
                    + "</code></pre>");
            out.append('\uE000').append(stash.size() - 1).append('\uE000');
            last = matcher.end();
        }
        out.append(input, last, input.length());
        return out.toString();
    }

    /**
     * 去掉标签内容开头**紧跟开标签的那一个换行**（含 {@code \r\n}）——与 MediaWiki 的
     * {@code <pre>} 规则一致：{@code <pre>} 后立刻换行不会渲染成第一行空白，其余内容原样保留。
     */
    static String stripLeadingNewline(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        if (content.startsWith("\r\n")) {
            return content.substring(2);
        }
        return content.startsWith("\n") || content.startsWith("\r") ? content.substring(1) : content;
    }

    /**
     * 去掉标签内容首尾的空行（含行尾的 {@code \r}），保留每行自身的缩进与中间的空行。
     * <p>
     * {@code "\n# 注释\njava -version\n\n"} → {@code "# 注释\njava -version"}。
     * <p>
     * 开标签后的第一个换行用 {@link #stripLeadingNewline} 剥（它只吃掉那一个换行）：
     * 若把「一个换行」和「一行空行」同样看待，{@code <pre>\n\n正文\n</pre>} 开头的空行会被吞掉，
     * 与 MediaWiki「只忽略紧跟开标签的那一个换行」的规则不符；
     * 后面的 {@code \n\n} 这类空行本身就剥不掉，保留下来正好。
     */
    static String stripEdgeBlankLines(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String text = stripLeadingNewline(content).replace("\r\n", "\n").replace('\r', '\n');
        int end = text.length();
        while (end > 0) {
            int lineStart = text.lastIndexOf('\n', end - 1);
            if (lineStart < 0 || !text.substring(lineStart + 1, end).isBlank()) {
                break;
            }
            end = lineStart;
        }
        return text.substring(0, end);
    }

    /** {@code <poem>…</poem>}（仿 Poem 扩展）：内部按行内 wikitext 解析，换行转 {@code <br>}，
     * 整段作为可信 HTML 块（{@code \uE004…\uE004}）在渲染最后还原。
     */
    private String extractPoem(String text, List<String> trusted) {
        Matcher matcher = POEM.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            String body = inline(matcher.group(1).trim());
            trusted.add("<div class=\"poem\">" + body.replace("\n", "<br>\n") + "</div>");
            out.append('\uE004').append(trusted.size() - 1).append('\uE004');
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /**
     * {@code <gallery>…</gallery>}（仿 MediaWiki 画廊）：每行一张图
     * （{@code 文件:甲.jpg|说明} 或 {@code 甲.jpg|说明}），整段作为可信 HTML 块还原。
     * <p>
     * 支持的属性：{@code widths}（图片最大宽度，默认 120）/ {@code heights}（最大高度）/
     * {@code perrow}（每行几张，网格排列）/ {@code mode}（traditional / nolines / packed）/
     * {@code caption}（画廊说明）/ {@code class}（附加类名，已转义）。
     * 文件不存在的行显示红链（与 {@code [[文件:…]]} 一致）；说明文字按行内 wikitext 解析。
     */
    private String extractGallery(String text, List<String> trusted) {
        Matcher matcher = GALLERY.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            trusted.add(renderGallery(matcher.group(1) == null ? "" : matcher.group(1),
                    matcher.group(2) == null ? "" : matcher.group(2)));
            out.append('\uE004').append(trusted.size() - 1).append('\uE004');
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** 渲染一个画廊（见 {@link #extractGallery}）。 */
    private String renderGallery(String attributeText, String body) {
        Map<String, String> params = new LinkedHashMap<>();
        Matcher attr = ATTR.matcher(attributeText);
        while (attr.find()) {
            String key = attr.group(1).toLowerCase(Locale.ROOT);
            String value = attr.group(2) != null ? attr.group(2)
                    : attr.group(3) != null ? attr.group(3)
                    : attr.group(4) != null ? attr.group(4) : "";
            params.put(key, value.trim());
        }
        int widths = option(params, "widths", 120, 20, 2000);
        int heights = option(params, "heights", 0, 0, 2000);
        int perrow = option(params, "perrow", 0, 0, 50);
        String mode = params.getOrDefault("mode", "").trim().toLowerCase(Locale.ROOT);
        String caption = params.getOrDefault("caption", "").trim();
        String userClass = params.getOrDefault("class", "").trim();

        StringBuilder html = new StringBuilder(2048);
        if (!caption.isEmpty()) {
            html.append("<div class=\"gallery-caption\">").append(inline(caption)).append("</div>");
        }
        StringBuilder classes = new StringBuilder("gallery");
        if ("nolines".equals(mode)) {
            classes.append(" mw-gallery-nolines");
        } else if (mode.startsWith("packed")) {
            classes.append(" mw-gallery-packed");
        }
        if (perrow > 0) {
            classes.append(" gallery-perrow");
        }
        if (!userClass.isEmpty()) {
            classes.append(' ').append(HtmlSanitizer.escapeAttribute(userClass));
        }
        html.append("<ul class=\"").append(classes).append('"');
        if (perrow > 0) {
            html.append(" style=\"grid-template-columns: repeat(").append(perrow)
                    .append(", minmax(0, 1fr))\"");
        }
        html.append('>');
        for (String rawLine : body.split("\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            int pipe = line.indexOf('|');
            String filePart = (pipe < 0 ? line : line.substring(0, pipe)).trim();
            String captionPart = pipe < 0 ? "" : line.substring(pipe + 1).trim();
            html.append(renderGalleryItem(filePart, captionPart, widths, heights));
        }
        html.append("</ul>");
        return html.toString();
    }

    /** 画廊里的一格：图片链到文件页（多媒体预览识别 {@code a.image} + {@code data-file}），说明在图片下方。 */
    private String renderGalleryItem(String filePart, String captionPart, int widths, int heights) {
        String fileName = galleryFileName(filePart);
        if (fileName.isEmpty()) {
            return "";
        }
        Optional<WikiFile> found = fileService == null ? Optional.empty() : fileService.findByName(fileName);
        if (found.isEmpty()) {
            String url = WikiUrls.pagePath(FileTitles.titleOf(fileName));
            return "<li class=\"gallerybox gallery-missing\"><a class=\"new\" href=\""
                    + HtmlSanitizer.escapeAttribute(url) + "\">"
                    + HtmlSanitizer.escapeText("文件:" + fileName) + "</a></li>";
        }
        WikiFile file = found.get();
        String alt = HtmlSanitizer.escapeText(captionPart.isEmpty() ? file.getName() : captionPart);
        String image = "<img class=\"wiki-media-image\" src=\""
                + HtmlSanitizer.escapeAttribute(fileService.urlOf(file)) + "\" alt=\"" + attributeText(alt) + "\""
                + " style=\"max-width:" + widths + "px" + (heights > 0 ? ";max-height:" + heights + "px" : "") + "\""
                + " data-file=\"" + attributeText(HtmlSanitizer.escapeText("文件:" + file.getName())) + "\""
                + (captionPart.isEmpty() ? ""
                : " data-caption=\"" + attributeText(HtmlSanitizer.escapeText(captionPart)) + "\"")
                + " loading=\"lazy\">";
        StringBuilder html = new StringBuilder(256);
        html.append("<li class=\"gallerybox\"><div class=\"thumb\"><div class=\"thumbinner\" style=\"width:")
                .append(widths).append("px\"><a class=\"image\" href=\"")
                .append(HtmlSanitizer.escapeAttribute(fileService.pageUrlOf(file))).append("\">")
                .append(image).append("</a>");
        if (!captionPart.isEmpty()) {
            html.append("<div class=\"thumbcaption\">").append(inline(captionPart)).append("</div>");
        }
        html.append("</div></div></li>");
        return html.toString();
    }

    /** 画廊行里的文件名：{@code 文件:甲.jpg} / {@code File:甲.jpg} / {@code 甲.jpg} → {@code 甲.jpg}。 */
    private static String galleryFileName(String raw) {
        String value = raw.trim();
        int colon = value.indexOf(':');
        if (colon > 0) {
            String prefix = value.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (List.of("文件", "file", "image", "图像", "圖片").contains(prefix)) {
                value = value.substring(colon + 1).trim();
            }
        }
        return value.replace(' ', '_');
    }

    /** 数字属性（带范围限制；非法 / 缺省用 fallback）。 */
    private static int option(Map<String, String> params, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(params.getOrDefault(key, "").trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /**
     * {@code <indicator name="…">…</indicator>}（仿 MediaWiki 页面状态指示器）：
     * 从正文剥离，内容按行内 wikitext 解析后写页面属性 {@code indicator-<名字>}
     * （同名只保留最后写入的那个），由 core 在标题旁渲染成小图标。
     */
    private String extractIndicators(String text) {
        if (text.indexOf("<indicator") < 0 && text.indexOf("<INDICATOR") < 0) {
            return text;
        }
        Matcher matcher = INDICATOR.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            String attributes = matcher.group(1) == null ? "" : matcher.group(1);
            String name = nameAttribute(attributes);
            String body = matcher.group(2) == null ? "" : matcher.group(2).trim();
            RenderContextState state = CURRENT.get();
            if (state != null && name != null && !name.isEmpty() && !body.isEmpty()) {
                state.setProperty("indicator-" + name, inline(body));
            }
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /** 从属性串里取命名属性（{@code pattern} 需带 1..3 组）。 */
    private static String attribute(String attributes, Pattern pattern) {
        Matcher matcher = pattern.matcher(attributes);
        if (!matcher.find()) {
            return "";
        }
        for (int group = 1; group <= 3; group++) {
            String value = matcher.group(group);
            if (value != null) {
                return value.trim();
            }
        }
        return "";
    }

    /** 本地化文案（无 MessageSource 时用兜底文案）。 */
    private String label(String key, String fallback) {
        return messageSource == null
                ? fallback
                : messageSource.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }

    private String protect(String input, Pattern pattern, List<String> stash,
                           java.util.function.Function<String, String> renderer) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(input, last, matcher.start());
            stash.add(renderer.apply(matcher.group(1)));
            out.append('\uE000').append(stash.size() - 1).append('\uE000');
            last = matcher.end();
        }
        out.append(input, last, input.length());
        return out.toString();
    }

    /**
     * 与 {@link #protect} 相同，但把「标签属性」（第 1 组，不含尖括号）与「标签内容」（第 2 组）
     * 一起交给渲染器——{@code <markdown class="…">} 这类既看属性又看内容的标签用。
     */
    private String protectTag(String input, Pattern pattern, List<String> stash, TagRenderer renderer) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(input, last, matcher.start());
            stash.add(renderer.render(matcher.group(1), matcher.group(2)));
            out.append('\uE000').append(stash.size() - 1).append('\uE000');
            last = matcher.end();
        }
        out.append(input, last, input.length());
        return out.toString();
    }

    /** 带属性的标签渲染器：参数依次是标签属性（可空）与标签内容。 */
    @FunctionalInterface
    private interface TagRenderer {

        String render(String attributes, String content);
    }

    // ==================== 工具 ====================

    private int findExpressionEnd(String text, int from) {
        int depth = 1;
        int i = from;
        while (i < text.length() - 1) {
            // {{{参数}}}：模板参数整体跳过，不参与 {{ }} 的深度（#invoke 参数里很常见）
            if (text.startsWith("{{{", i)) {
                int parameterClose = text.indexOf("}}}", i + 3);
                if (parameterClose < 0) {
                    return -1;
                }
                i = parameterClose + 3;
                continue;
            }
            if (text.charAt(i) == '{' && text.charAt(i + 1) == '{') {
                depth++;
                i += 2;
            } else if (text.charAt(i) == '}' && text.charAt(i + 1) == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
                i += 2;
            } else {
                i++;
            }
        }
        return -1;
    }

    private List<String> splitTopLevel(String text, char delimiter) {
        List<String> parts = new ArrayList<>();
        int braceDepth = 0;
        int bracketDepth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                braceDepth++;
                i++;
            } else if (c == '}' && i + 1 < text.length() && text.charAt(i + 1) == '}') {
                braceDepth--;
                i++;
            } else if (c == '[' && i + 1 < text.length() && text.charAt(i + 1) == '[') {
                bracketDepth++;
                i++;
            } else if (c == ']' && i + 1 < text.length() && text.charAt(i + 1) == ']') {
                bracketDepth--;
                i++;
            } else if (c == delimiter && braceDepth == 0 && bracketDepth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private int indexOfTopLevel(String text, char target) {
        int braceDepth = 0;
        int bracketDepth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                braceDepth++;
                i++;
            } else if (c == '}' && i + 1 < text.length() && text.charAt(i + 1) == '}') {
                braceDepth--;
                i++;
            } else if (c == '[' && i + 1 < text.length() && text.charAt(i + 1) == '[') {
                bracketDepth++;
                i++;
            } else if (c == ']' && i + 1 < text.length() && text.charAt(i + 1) == ']') {
                bracketDepth--;
                i++;
            } else if (c == target && braceDepth == 0 && bracketDepth == 0) {
                return i;
            }
        }
        return -1;
    }

    private String shortPageName(String pageTitle) {
        String title = pageTitle == null ? "" : pageTitle;
        int colon = title.indexOf(':');
        return colon < 0 ? title : title.substring(colon + 1);
    }

    /** MediaWiki 的 {{NAMESPACE}}：命名空间名（不含冒号）。 */
    private String namespaceOf(String pageTitle) {
        String title = pageTitle == null ? "" : pageTitle;
        int colon = title.indexOf(':');
        return colon < 0 ? "" : title.substring(0, colon);
    }

    /** {{SUBPAGENAME}}：最后一个 / 之后的片段。 */
    private static String subpageName(String title) {
        int slash = title.lastIndexOf('/');
        return slash < 0 ? title : title.substring(slash + 1);
    }

    /** {{BASEPAGENAME}}：去掉最后一个子页面片段（保留命名空间）。 */
    private static String basePageName(String title) {
        int slash = title.lastIndexOf('/');
        return slash < 0 ? title : title.substring(0, slash);
    }

    /** {{ROOTPAGENAME}} 的根部分：第一个 / 之前（含命名空间）。 */
    private static String rootPageName(String title) {
        int slash = title.indexOf('/');
        return slash < 0 ? title : title.substring(0, slash);
    }

    /** PAGENAMEE 等：空格转下划线，其余按 URL 规则百分号编码。 */
    private static String encodeTitle(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "_");
    }

    private record TemplateSource(String title, String content) {
    }

    private record TableData(String attributes, List<String> tableLines, int nextIndex) {
    }

    private record Cell(boolean header, String content) {
    }
}