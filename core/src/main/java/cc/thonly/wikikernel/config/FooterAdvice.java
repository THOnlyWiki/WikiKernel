package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.service.SiteNameService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.wikitext.WikitextRenderer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 页脚左右两块内容的控制（仿 MediaWiki 的页脚消息页）：
 * <ul>
 *     <li>{@code <站点名称>:Footer-Left}：左侧内容，替换内置的三个链接
 *     （隐私政策 / 关于站点名称 / 免责声明）；</li>
 *     <li>{@code <站点名称>:Footer-Right}：右侧内容，替换内置的语言链接区。</li>
 * </ul>
 * 页面不存在或内容为空时模型属性为 null，模板回退到内置内容；内容按 wikitext 渲染后注入
 * （已转义 / 白名单过滤）。旧版 {@code WikiKernel:Footer-Left/Right} 仍作为兼容回退。
 */
@ControllerAdvice
public class FooterAdvice {

    /** 兼容旧版页脚页标题。 */
    public static final String LEGACY_FOOTER_LEFT_PAGE = "WikiKernel:Footer-Left";
    public static final String LEGACY_FOOTER_RIGHT_PAGE = "WikiKernel:Footer-Right";

    /** 站点命名空间下的页脚页后缀。 */
    public static final String FOOTER_LEFT_SUFFIX = "Footer-Left";
    public static final String FOOTER_RIGHT_SUFFIX = "Footer-Right";

    private final WikiPageService wikiPageService;
    private final WikitextRenderer wikitextRenderer;
    private final SiteNameService siteNameService;

    public FooterAdvice(WikiPageService wikiPageService, WikitextRenderer wikitextRenderer,
                        SiteNameService siteNameService) {
        this.wikiPageService = wikiPageService;
        this.wikitextRenderer = wikitextRenderer;
        this.siteNameService = siteNameService;
    }

    /** 页脚左侧：站点名称:Footer-Left 的渲染结果（不存在 / 为空 = null）。 */
    @ModelAttribute("footerLeftHtml")
    public String footerLeftHtml(HttpServletRequest request) {
        return renderFooterPage(request, FOOTER_LEFT_SUFFIX, LEGACY_FOOTER_LEFT_PAGE);
    }

    /** 页脚右侧：站点名称:Footer-Right 的渲染结果（不存在 / 为空 = null）。 */
    @ModelAttribute("footerRightHtml")
    public String footerRightHtml(HttpServletRequest request) {
        return renderFooterPage(request, FOOTER_RIGHT_SUFFIX, LEGACY_FOOTER_RIGHT_PAGE);
    }

    private String renderFooterPage(HttpServletRequest request, String suffix, String legacyTitle) {
        // API 请求不渲染模板，省掉两次查询
        if (request.getRequestURI().startsWith("/api/")) {
            return null;
        }
        String siteName = siteNameService.name();
        String rendered = renderPage(siteName + ":" + suffix);
        if (rendered == null && !"WikiKernel".equals(siteName)) {
            rendered = renderPage(legacyTitle);
        }
        return rendered;
    }

    private String renderPage(String title) {
        return wikiPageService.getVisibleByTitle(title)
                .flatMap(page -> wikiPageService.getLatestContent(page.getId()))
                .filter(content -> !content.isBlank())
                .map(content -> wikitextRenderer.renderWithMeta(content, title).html())
                .orElse(null);
    }
}
