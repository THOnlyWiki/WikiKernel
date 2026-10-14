package cc.thonly.wikikernel.sidebar;

import cc.thonly.wikikernel.web.WikiTitles;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 把侧边栏放进所有 MVC 模板的模型里（模型属性名 {@code sidebar}），
 * 并用**当前页面标题**替换侧栏页面里的 {@code $title$} / {@code {{PAGENAME}}} 占位
 * （标题从请求 URI 解析；错误页取原始请求 URI）。
 */
@ControllerAdvice
public class SidebarModelAdvice {

    private final SidebarService sidebarService;

    public SidebarModelAdvice(SidebarService sidebarService) {
        this.sidebarService = sidebarService;
    }

    @ModelAttribute("sidebar")
    public List<SidebarSection> sidebar(HttpServletRequest request) {
        return sidebarService.sidebar(currentTitle(request));
    }

    /** 从请求 URI 解析当前页面标题；解析不到返回空串（此时侧栏不显示依赖当前页面的条目）。 */
    private static String currentTitle(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!isWikiPath(uri)) {
            Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
            uri = original instanceof String value && isWikiPath(value) ? value : null;
        }
        if (uri == null) {
            return "";
        }
        String raw = uri.substring("/wiki/".length());
        int query = raw.indexOf('?');
        if (query >= 0) {
            raw = raw.substring(0, query);
        }
        try {
            return WikiTitles.normalize(UriUtils.decode(raw, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private static boolean isWikiPath(String uri) {
        return uri != null && uri.startsWith("/wiki/");
    }
}
