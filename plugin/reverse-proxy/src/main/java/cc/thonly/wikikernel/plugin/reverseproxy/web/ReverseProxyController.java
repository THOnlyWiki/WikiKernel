package cc.thonly.wikikernel.plugin.reverseproxy.web;

import cc.thonly.wikikernel.plugin.reverseproxy.ReverseProxySupport;
import cc.thonly.wikikernel.plugin.reverseproxy.service.ReverseProxyException;
import cc.thonly.wikikernel.plugin.reverseproxy.service.ReverseProxyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;

/**
 * 动态代理入口：{@code /proxy_<slug>/...}。
 *
 * <p>使用固定命名空间 {@code proxy_} 而不是任意路径，避免和 Wiki 自己的
 * {@code /api/**}、{@code /wiki/**}、静态资源路由冲突。实际目标只由管理员配置的
 * route 决定；路径后缀只作为上游路径，不会改变 host。</p>
 */
@Controller
public class ReverseProxyController {

    private final ReverseProxyService service;
    private final ReverseProxySupport support;

    public ReverseProxyController(ReverseProxyService service, ReverseProxySupport support) {
        this.service = service;
        this.support = support;
    }

    @RequestMapping({"/proxy_{slug}", "/proxy_{slug}/**"})
    public void proxy(@PathVariable("slug") String slug,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        try {
            service.proxy(slug, request, response);
        } catch (ReverseProxyException exception) {
            if (!response.isCommitted()) {
                response.reset();
                response.setHeader("Cache-Control", "no-store");
                response.sendError(exception.status(), support.message(exception.messageKey()));
            }
        } catch (IOException exception) {
            if (!response.isCommitted()) {
                response.reset();
                response.setHeader("Cache-Control", "no-store");
                response.sendError(HttpServletResponse.SC_BAD_GATEWAY,
                        support.message("reverseProxy.upstreamError"));
            }
        } catch (RuntimeException exception) {
            if (!response.isCommitted()) {
                response.reset();
                response.setHeader("Cache-Control", "no-store");
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        support.message("reverseProxy.operationFailed"));
            }
        }
    }
}
