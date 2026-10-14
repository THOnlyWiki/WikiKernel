package cc.thonly.wikikernel.skin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 把当前请求生效的皮肤注入所有 MVC 模板：{@code ${skin.layout}} / {@code ${skin.stylesheets}} 等。
 */
@ControllerAdvice
public class SkinAdvice {

    private final SkinRegistry skinRegistry;

    public SkinAdvice(SkinRegistry skinRegistry) {
        this.skinRegistry = skinRegistry;
    }

    @ModelAttribute("skin")
    public ActiveSkin skin(HttpServletRequest request) {
        return skinRegistry.activeView(request);
    }
}
