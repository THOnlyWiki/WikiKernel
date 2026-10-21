package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.actionapi.ActionApiService;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code 特殊:ApiSandbox} / {@code Special:ApiSandbox}：仿 MediaWiki ApiSandbox 的
 * Action API 请求构造器。页面只负责收集参数并在当前浏览器会话里请求 {@code /api.php}，
 * 展示请求 URL 与原始响应；写操作会自动附带当前会话的 CSRF token。
 */
@Component
public class ApiSandboxSpecialPage implements SpecialPage {

    public static final String PAGE_NAME = "ApiSandbox";

    private final ActionApiService actionApiService;
    private final MessageSource messageSource;

    public ApiSandboxSpecialPage(ActionApiService actionApiService, MessageSource messageSource) {
        this.actionApiService = actionApiService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return PAGE_NAME;
    }

    @Override
    public String displayName() {
        return message("special.apiSandbox");
    }

    @Override
    public List<String> aliases() {
        return List.of("API沙盒", "接口沙盒", "APISandbox");
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    public String template() {
        return "special/api-sandbox";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Map<String, String> parameters = request.parameters() == null ? Map.of() : request.parameters();
        String defaultAction = parameters.getOrDefault("action", "query");
        String defaultFormat = parameters.getOrDefault("format", "json");
        if (!List.of("json", "jsonfm", "xml").contains(defaultFormat)) {
            defaultFormat = "json";
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (String action : actionApiService.actionNames()) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("name", action);
            option.put("selected", action.equalsIgnoreCase(defaultAction));
            actions.add(option);
        }
        model.put("actionOptions", actions);
        model.put("defaultAction", defaultAction);
        model.put("defaultFormat", defaultFormat);
        model.put("formatVersion2", !"1".equals(parameters.get("formatversion")));
        model.put("loggedIn", request.user() != null);
        model.put("sandboxUser", request.user() == null ? "" : request.user().getUsername());
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
