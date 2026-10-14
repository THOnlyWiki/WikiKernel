package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.web.WikiUrls;
import cc.thonly.wikikernel.wikitext.WikitextRenderer;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 自定义错误页（取代 Spring Boot 的 whitelabel 错误页）：
 * <ul>
 *     <li><b>404</b>：把 {@code WikiKernel:404} 页面的渲染结果插入 404 页面；页面内容支持占位符
 *         {@code $TITLE$}（被访问的标题）、{@code $SEARCH_URL$} / {@code $LOGS_URL$} / {@code $CREATE_URL$}
 *         （搜索本页标题 / 搜索相关日志 / 创建本页面的链接），渲染前替换；
 *         h1 与浏览器标题显示被访问的标题（拿不到时用「页面不存在」）；</li>
 *     <li>页面不存在 / 无法读取 / 内容为空时用内置提示（同样带上面三个蓝链）；</li>
 *     <li>其它状态码：通用错误页（状态码 + 本地化提示）。</li>
 * </ul>
 * 状态码保持原样返回（404 响应体是自定义页面，HTTP 状态仍是 404）。
 */
@Controller
public class WikiErrorController implements ErrorController {

    /** 404 页面内容的来源页面（仿 MediaWiki 的消息页）。 */
    public static final String NOT_FOUND_PAGE = "WikiKernel:404";

    private final WikiPageService wikiPageService;
    private final WikitextRenderer wikitextRenderer;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public WikiErrorController(WikiPageService wikiPageService,
                               WikitextRenderer wikitextRenderer,
                               AccessControlService accessControlService,
                               MessageSource messageSource) {
        this.wikiPageService = wikiPageService;
        this.wikitextRenderer = wikitextRenderer;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ModelAndView handleError(HttpServletRequest request, HttpServletResponse response) {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusAttribute instanceof Integer value ? value : HttpStatus.INTERNAL_SERVER_ERROR.value();
        response.setStatus(status);
        if (status == HttpStatus.NOT_FOUND.value()) {
            ModelAndView modelAndView = notFound(request);
            addOriginalPath(request, modelAndView);
            return modelAndView;
        }
        ModelAndView modelAndView = new ModelAndView("error/error");
        modelAndView.addObject("errorTitle", message("error.generic.title"));
        modelAndView.addObject("status", status);
        modelAndView.addObject("errorMessage", errorMessage(request, status));
        addOriginalPath(request, modelAndView);
        return modelAndView;
    }

    /** 4xx 优先显示主动抛出的原因（如「您没有权限永久删除此文件。」）；其余用通用提示。 */
    private String errorMessage(HttpServletRequest request, int status) {
        if (status >= 400 && status < 500) {
            Object reason = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
            if (reason instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return message("error.generic.message", status);
    }

    /** 页脚语言切换用：把 currentPath 指回触发错误的原始地址（不带查询串），而不是 /error。 */
    private static void addOriginalPath(HttpServletRequest request, ModelAndView modelAndView) {
        Object originalUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (originalUri instanceof String uri && !uri.isBlank()) {
            int query = uri.indexOf('?');
            modelAndView.addObject("currentPath", query < 0 ? uri : uri.substring(0, query));
        }
    }

    /**
     * 404：{@code WikiKernel:404} 的渲染结果（没有 / 空 / 不可读则内置提示）。
     * 两者都带上「搜索本页标题 / 搜索相关日志 / 创建本页面」三个蓝链。
     */
    private ModelAndView notFound(HttpServletRequest request) {
        String title = requestedTitle(request);
        String searchUrl = WikiUrls.pagePath("特殊:搜索") + (title == null ? "" : "?q=" + encode(title));
        String logsUrl = WikiUrls.pagePath("特殊:日志") + (title == null ? "" : "?page=" + encode(title));
        String createUrl = title == null
                ? WikiUrls.pagePath("特殊:创建页面")
                : WikiUrls.pagePath(title) + "?action=edit";

        WikiUser viewer = UserSession.current(request);
        String contentHtml = null;
        if (this.accessControlService.canRead(NOT_FOUND_PAGE, viewer)) {
            Optional<WikiPage> page = this.wikiPageService.getVisibleByTitle(NOT_FOUND_PAGE);
            if (page.isPresent()) {
                String content = this.wikiPageService.getLatestContent(page.get().getId()).orElse("");
                if (!content.isBlank()) {
                    content = content
                            .replace("$TITLE$", title == null ? "" : title)
                            .replace("$SEARCH_URL$", searchUrl)
                            .replace("$LOGS_URL$", logsUrl)
                            .replace("$CREATE_URL$", createUrl);
                    contentHtml = this.wikitextRenderer.render(content, NOT_FOUND_PAGE);
                }
            }
        }
        ModelAndView modelAndView = new ModelAndView("error/404");
        // 仿 MediaWiki：404 页的 h1 与浏览器标题显示被访问的标题（拿不到标题时才用「页面不存在」）
        modelAndView.addObject("errorTitle", title == null || title.isBlank()
                ? message("error.404.title") : title);
        modelAndView.addObject("notFoundContent", contentHtml);
        modelAndView.addObject("defaultNotFoundContent", defaultNotFoundHtml(searchUrl, logsUrl, createUrl));
        return modelAndView;
    }

    /** 内置提示：文字与默认页面内容一致，三个短语是可点击蓝链。 */
    private String defaultNotFoundHtml(String searchUrl, String logsUrl, String createUrl) {
        String line = message("error.404.line1",
                anchor(searchUrl, message("error.404.searchTitle")),
                anchor(logsUrl, message("error.404.searchLogs")),
                anchor(createUrl, message("error.404.createPage")));
        return "<p>" + line + "</p><p>" + HtmlUtils.htmlEscape(message("error.404.note")) + "</p>";
    }

    private static String anchor(String url, String text) {
        return "<a href=\"" + HtmlUtils.htmlEscape(url) + "\">" + HtmlUtils.htmlEscape(text) + "</a>";
    }

    /** 从错误请求 URI 里取被访问的标题（{@code /wiki/标题} → 标题；其它路径 / 解析失败 = null）。 */
    private static String requestedTitle(HttpServletRequest request) {
        Object originalUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (!(originalUri instanceof String uri) || uri.isBlank()) {
            return null;
        }
        String path = uri;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (!path.startsWith(WikiUrls.WIKI_PREFIX)) {
            return null;
        }
        String rest = path.substring(WikiUrls.WIKI_PREFIX.length());
        if (rest.isBlank()) {
            return null;
        }
        try {
            // 只做百分号解码（UriUtils）：路径里的 '+' 是字面加号，不能被当成空格（URLDecoder 是表单语义）
            String decoded = Arrays.stream(rest.split("/", -1))
                    .map(segment -> UriUtils.decode(segment, StandardCharsets.UTF_8))
                    .collect(Collectors.joining("/"));
            return WikiTitles.normalize(decoded);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
