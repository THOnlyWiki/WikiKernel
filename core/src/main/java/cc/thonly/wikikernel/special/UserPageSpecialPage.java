package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:用户?name=用户名} / {@code Special:User}：兼容入口。
 * <p>
 * 用户页现在位于用户命名空间（{@code 用户:用户名} / {@code User:用户名}），是普通 Wiki 页面（可创建、可编辑，
 * 编辑权限见访问规则与站点设置）；本特殊页只把带 {@code name} 的访问 302 到对应命名空间页面，
 * 不带 name 时显示一个输入框。
 */
@Component
public class UserPageSpecialPage implements SpecialPage {

    /** 查询参数名：用户名。 */
    public static final String PARAM_NAME = "name";

    private final MessageSource messageSource;

    public UserPageSpecialPage(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "用户";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.user", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("User", "UserPage", "用户页");
    }

    @Override
    public String template() {
        return "special/user";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        String username = Strings.trimToEmpty(request.parameters().get(PARAM_NAME));
        if (!username.isEmpty()) {
            // 用户页 = 用户命名空间页面：302 到 用户:<用户名>
            model.put(REDIRECT_MODEL_ATTRIBUTE, WikiUrls.pagePath("用户:" + username));
            return;
        }
        model.put("lookupRequired", true);
    }
}
