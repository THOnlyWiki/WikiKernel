package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.service.SystemInterfaceService;
import cc.thonly.wikikernel.transfer.TransferNamespaces;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.WikiTitles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Locale;
import java.util.Map;

/** 每个 HTML 请求只计算一次；页面正文缓存不缓存按登录状态选择的公告。 */
@ControllerAdvice
public class SystemInterfaceAdvice {
    public record View(String noticeHtml, String taglineHtml, String editNoticeHtml) {
        static final View EMPTY = new View(null, null, null);
    }

    private final SystemInterfaceService service;

    public SystemInterfaceAdvice(SystemInterfaceService service) {
        this.service = service;
    }

    @ModelAttribute("systemInterface")
    public View messages(HttpServletRequest request, Locale locale) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/") || uri.equals("/api.php") || uri.startsWith("/plugins/")
                || uri.startsWith("/skins/") || uri.startsWith("/avatars/")
                || uri.startsWith("/css/") || uri.startsWith("/js/") || uri.startsWith("/img/")
                || uri.startsWith("/vendor/")) {
            return View.EMPTY;
        }
        var viewer = UserSession.current(request);
        String notice = viewer == null ? service.render("Anonnotice", locale, null) : null;
        if (notice == null) {
            notice = service.render("Sitenotice", locale, viewer);
        }
        String tagline = service.render("Tagline", locale, viewer);
        String editNotice = null;
        if ("edit".equals(request.getParameter("action")) || "POST".equals(request.getMethod())) {
            Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            if (attribute instanceof Map<?, ?> variables && variables.get("title") != null) {
                String title = variables.get("title").toString();
                Object rest = variables.get("rest");
                title = WikiTitles.normalize(title + (rest == null ? "" : rest.toString()));
                String global = service.render("Editnotice", locale, viewer);
                String specific = service.render("Editnotice-" + TransferNamespaces.mediaWikiId(title), locale, viewer);
                if (global != null || specific != null) {
                    editNotice = (global == null ? "" : global) + (specific == null ? "" : specific);
                }
            }
        }
        return new View(notice, tagline, editNotice);
    }
}
