package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.wikitext.WikitextRenderer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 页脚左右两块内容的控制（仿 MediaWiki 的页脚消息页）：
 * <ul>
 *     <li>{@code WikiKernel:Footer-Left}：左侧内容，替换内置的三个链接（隐私政策 / 关于WikiKernel / 免责声明）；</li>
 *     <li>{@code WikiKernel:Footer-Right}：右侧内容，替换内置的语言链接区。</li>
 * </ul>
 * 页面不存在或内容为空时模型属性为 null，模板回退到内置内容；内容按 wikitext 渲染后注入（已转义 / 白名单过滤）。
 */
@ControllerAdvice
public class FooterAdvice {

    public static final String FOOTER_LEFT_PAGE = "WikiKernel:Footer-Left";
    public static final String FOOTER_RIGHT_PAGE = "WikiKernel:Footer-Right";

    private final WikiPageService wikiPageService;
    private final WikitextRenderer wikitextRenderer;

    public FooterAdvice(WikiPageService wikiPageService, WikitextRenderer wikitextRenderer) {
        this.wikiPageService = wikiPageService;
        this.wikitextRenderer = wikitextRenderer;
    }

    /** 页脚左侧：WikiKernel:Footer-Left 的渲染结果（不存在 / 为空 = null）。 */
    @ModelAttribute("footerLeftHtml")
    public String footerLeftHtml(HttpServletRequest request) {
        return renderFooterPage(request, FOOTER_LEFT_PAGE);
    }

    /** 页脚右侧：WikiKernel:Footer-Right 的渲染结果（不存在 / 为空 = null）。 */
    @ModelAttribute("footerRightHtml")
    public String footerRightHtml(HttpServletRequest request) {
        return renderFooterPage(request, FOOTER_RIGHT_PAGE);
    }

    private String renderFooterPage(HttpServletRequest request, String title) {
        // API 请求不渲染模板，省掉两次查询
        if (request.getRequestURI().startsWith("/api/")) {
            return null;
        }
        return wikiPageService.getVisibleByTitle(title)
                .flatMap(page -> wikiPageService.getLatestContent(page.getId()))
                .filter(content -> !content.isBlank())
                .map(content -> wikitextRenderer.renderWithMeta(content, title).html())
                .orElse(null);
    }
}
