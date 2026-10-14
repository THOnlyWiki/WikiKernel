package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:创建页面} / {@code Special:CreatePage}：
 * 只有一个标题输入框和“创建”按钮，提交后 302 跳转到该标题的编辑页
 * （页面不存在则保存时创建；未登录会先被编辑页引导去登录）。
 */
@Component
public class CreatePageSpecialPage implements SpecialPage {

    /** 表单参数名（不叫 title，避免和模板模型里的标题混淆）。 */
    public static final String PARAM_PAGE_NAME = "pagename";

    private final MessageSource messageSource;

    public CreatePageSpecialPage(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "创建页面";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.createPage", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("CreatePage", "NewPage");
    }

    @Override
    public String template() {
        return "special/create-page";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        String raw = request.parameters().get(PARAM_PAGE_NAME);
        if (raw == null) {
            return; // 首次打开：只显示表单
        }
        String pageName = Strings.trimToEmpty(raw);
        model.put("pagename", pageName);
        if (pageName.isEmpty()) {
            model.put("pagenameRequired", true);
            return;
        }
        // 非法字符 / 过长 / 相对路径等：留在表单上提示（仿 MediaWiki「标题无效」）
        WikiTitles.validate(pageName).ifPresent(error -> model.put("titleInvalid",
                messageSource.getMessage(error.messageKey(), new Object[]{error.detail()},
                        LocaleContextHolder.getLocale())));
        if (model.containsKey("titleInvalid")) {
            return;
        }
        model.put(REDIRECT_MODEL_ATTRIBUTE, WikiUrls.pagePath(WikiTitles.normalize(pageName)) + "?action=edit");
    }
}
