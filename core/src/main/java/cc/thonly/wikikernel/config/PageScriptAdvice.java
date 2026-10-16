package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.skin.SkinRegistry;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.wikitext.CssSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;

/**
 * 把站点 / 页面自定义脚本与样式注入所有 MVC 模板的模型（属性名 {@code scriptUrls} / {@code pageCss}）：
 * <ul>
 *     <li>全站脚本：{@code WikiKernel:Wiki.js} + {@code WikiKernel:Common.js}（每个页面都加载）；</li>
 *     <li>按皮肤生效的站点脚本：{@code WikiKernel:<当前皮肤 id>.js}（如 {@code WikiKernel:plain.js}）；</li>
 *     <li>页面脚本：{@code {当前页面标题}/任意.js} 子页面（如 {@code 首页/脚本.js}）；</li>
 *     <li>页面样式：{@code Wiki.css} + {@code WikiKernel:Common.css} + {@code WikiKernel:<皮肤>.css}
 *     + {@code {当前页面标题}/XXX.css}（{@code pageCss}，由 head.html 内联到 {@code <head>}）。</li>
 * </ul>
 * 脚本通过 {@code ?action=raw&ctype=text/javascript} 以 {@code <script src>} 外部加载，
 * 不内联，避免 {@code </script>} 转义问题（head.html 里渲染）。
 * <p>
 * {@code pageCss} 在这里对所有页面外壳生效（阅读 / 编辑 / 历史 / 差异 / 分类 / 文件 / 特殊页…），
 * 否则非阅读页会缺站点样式（例如皮肤页里给侧栏 {@code #n-*} 条目配的图标与文字颜色）。
 * 阅读与差异页在控制器里还会按**解析后的规范标题**再设一次（重定向目标 / 标题规范化）；
 * {@code @ModelAttribute} 先于控制器执行，控制器设置的值会覆盖这里的值。
 * <p>
 * Gadgets 已迁到插件 {@code gadgets}：Gadget 的脚本 / 样式与依赖声明由插件的
 * {@code HtmlHeadProvider}（站点级）注入，这里不再涉及。
 */
@ControllerAdvice
public class PageScriptAdvice {

    private final WikiPageService wikiPageService;
    private final SkinRegistry skinRegistry;

    public PageScriptAdvice(WikiPageService wikiPageService, SkinRegistry skinRegistry) {
        this.wikiPageService = wikiPageService;
        this.skinRegistry = skinRegistry;
    }

    @ModelAttribute("scriptUrls")
    public List<String> scriptUrls(HttpServletRequest request) {
        // API 请求不渲染模板，省掉两次查询
        if (request.getRequestURI().startsWith("/api/")) {
            return List.of();
        }
        // 皮肤与模板里的 ${skin.id} 同一来源（SkinRegistry），保证同一请求内渲染与注入用同一个皮肤
        String skinId = skinRegistry.active(request).id();
        return wikiPageService.getJS(currentTitle(request), skinId).stream()
                .map(PageScriptAdvice::rawUrl)
                .toList();
    }

    /** 页面级自定义样式（含全站与皮肤页）：所有页面外壳都要带上，见类注释。 */
    @ModelAttribute("pageCss")
    public List<String> pageCss(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/") || uri.startsWith("/plugins/") || uri.startsWith("/avatars/")) {
            return List.of();
        }
        String skinId = skinRegistry.active(request).id();
        return wikiPageService.getCSS(currentTitle(request), skinId).stream()
                .map(CssSanitizer::sanitize)
                .filter(css -> !css.isBlank())
                .toList();
    }

    /** 从 URI 模板变量里取当前页面标题（路由为 /wiki/{title} 与 /wiki/{title}/{*rest}）。 */
    private static String currentTitle(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> variables)) {
            return null;
        }
        Object title = variables.get("title");
        if (title == null) {
            return null;
        }
        String fullTitle = title.toString();
        Object rest = variables.get("rest");
        if (rest != null && !rest.toString().isEmpty()) {
            fullTitle = fullTitle + "/" + rest;
        }
        return fullTitle;
    }

    private static String rawUrl(String title) {
        return WikiUrls.pagePath(title) + "?action=raw&ctype=text/javascript";
    }
}
