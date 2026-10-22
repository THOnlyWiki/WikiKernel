package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.web.SafeReturnTo;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:修改密码} / {@code Special:ChangePassword}（仿 MediaWiki 的 {@code Special:ChangePassword} /
 * {@code Special:ChangeCredentials}）：
 * <p>
 * 线上「参数设置」里「密码」一栏是一个白色的 OOUI 按钮「更改密码」，点进本页填
 * 当前密码 / 新密码 / 确认密码；表单由 {@code /js/change-password.js} 提交到
 * {@code POST /api/user/preferences/password}（core 既有的改密接口，会校验旧密码），
 * 成功后回到 {@code returnto}（默认 {@code 特殊:参数设置}）。
 * <p>
 * 未登录时页面只显示「请先登录」提示（真实校验还是靠接口的登录检查）。
 */
@Component
public class ChangePasswordSpecialPage implements SpecialPage {

    /** 改密成功后默认回到的页面（线上 OOUI 按钮带的就是这个 returnto）。 */
    public static final String DEFAULT_RETURNTO = "特殊:参数设置";

    private final MessageSource messageSource;

    public ChangePasswordSpecialPage(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "修改密码";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.changePassword", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("ChangePassword", "更改密码");
    }

    @Override
    public String category() {
        return SpecialPageCategories.LOGIN;
    }

    @Override
    public String template() {
        return "special/change-password";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        model.put("changePasswordLogin", user == null);
        if (user != null) {
            model.put("accountName", user.getUsername());
        }
        model.put("returnto", safeReturnto(request.parameters().get("returnto")));
    }

    /**
     * 只接受站内路径 / 参数（防止把 {@code returnto} 用成开放重定向）：
     * 以 {@code /} 开头且不是 {@code //host} 的路径，或者没带协议前缀的纯标题。
     */
    static String safeReturnto(String value) {
        return SafeReturnTo.titleOrPath(value, "/wiki/" + DEFAULT_RETURNTO);
    }
}
