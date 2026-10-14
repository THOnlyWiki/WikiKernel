package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.service.SiteNameService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 把当前站点名称（{@code ${siteName}}）注入所有 MVC 模板：
 * 皮肤布局用它拼浏览器标题（「页面名 - 站点名」）、页眉图标提示与搜索框占位符等。
 */
@ControllerAdvice
public class SiteNameAdvice {

    private final SiteNameService siteNameService;

    public SiteNameAdvice(SiteNameService siteNameService) {
        this.siteNameService = siteNameService;
    }

    @ModelAttribute("siteName")
    public String siteName() {
        return siteNameService.name();
    }
}
