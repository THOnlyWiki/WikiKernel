package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.web.CodexIconService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:CodexExample}：Wikimedia Codex 组件 / 图标总览。
 * 页面本身由站点设置 {@code features.codex-example} 控制是否注册（默认开启）。
 */
@Component
public class CodexExampleSpecialPage implements SpecialPage {

    public static final String PAGE_NAME = "CodexExample";

    private final FeatureService features;
    private final CodexIconService icons;
    private final MessageSource messageSource;

    public CodexExampleSpecialPage(FeatureService features, CodexIconService icons,
                                   MessageSource messageSource) {
        this.features = features;
        this.icons = icons;
        this.messageSource = messageSource;
    }

    @Override
    public boolean enabled() {
        return features.codexExampleEnabled();
    }

    @Override
    public String name() {
        return PAGE_NAME;
    }

    @Override
    public String displayName() {
        return message("special.codexExample");
    }

    @Override
    public List<String> aliases() {
        return List.of("Codex示例", "Codex组件示例");
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    public String template() {
        return "special/codex-example";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        List<Map<String, String>> allIcons = icons.examples();
        model.put("codexIcons", allIcons);
        model.put("iconAdd", icons.icon("add"));
        model.put("iconCheck", icons.icon("check"));
        model.put("iconAlert", icons.icon("alert"));
        model.put("iconError", icons.icon("error"));
        model.put("iconArticle", icons.icon("article"));
        model.put("codexIconCount", allIcons.size());
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
