package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.extension.PersonalToolbarContext;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.service.PersonalToolbarHookService;
import cc.thonly.wikikernel.service.SiteNameService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/**
 * 顶栏个人工具区（{@code templates/fragments/personal-tools.html}）的 core 侧接线：
 * 问插件 {@code PersonalToolbarHook} 要额外条目（如「私信（3）」未读计数），
 * 放进模型键 {@code pluginPersonalTools}，模板在最后一项之后逐个渲染。
 * <p>
 * 插件未加载时列表为空，个人工具区与迁移前完全一致；core 不再自己算未读私信数
 * （迁移前由 {@code CurrentUserAdvice.unreadMessages} 提供）。
 */
@ControllerAdvice
public class PersonalToolbarAdvice {

    private final CurrentUser currentUser;
    private final SiteNameService siteNameService;
    private final PersonalToolbarHookService personalToolbarHooks;

    public PersonalToolbarAdvice(CurrentUser currentUser, SiteNameService siteNameService,
                                 PersonalToolbarHookService personalToolbarHooks) {
        this.currentUser = currentUser;
        this.siteNameService = siteNameService;
        this.personalToolbarHooks = personalToolbarHooks;
    }

    @ModelAttribute("pluginPersonalTools")
    public List<String> pluginPersonalTools(HttpServletRequest request) {
        if (isNonPageRequest(request)) {
            return List.of();
        }
        Viewer viewer = this.currentUser.viewer();
        PersonalToolbarContext context = new RequestPersonalToolbarContext(
                viewer, this.siteNameService.name(), RequestHeadContext.buildBaseUrl(request));
        return this.personalToolbarHooks.itemsHtml(context);
    }

    /** API / 插件静态资源 / 头像文件请求不渲染模板，直接跳过（钩子不必为它们做数据库查询）。 */
    private static boolean isNonPageRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || uri.startsWith("/api/") || uri.startsWith("/api.php")
                || uri.startsWith("/plugins/") || uri.startsWith("/avatars/");
    }
}
