package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.TransferProperties;
import cc.thonly.wikikernel.service.WikiExportService;
import cc.thonly.wikikernel.web.WikiNamespaces;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:导出页面} / {@code Special:Export}（仿 MediaWiki Special:Export）：
 * 输入页面标题（每行一个）、勾选完整历史与模板、选择格式（MediaWiki XML / WikiKernel XML / JSON），
 * 表单提交到 {@code POST /api/export} 直接下载文件。
 * <p>
 * 「添加命名空间全部页面」走服务端：{@code ?addns=<命名空间id>} 把可查看的全部标题填进文本框。
 */
@Component
public class ExportSpecialPage implements SpecialPage {

    /** 「保持原命名空间」在导入页里的下拉值（导出页的 addns 不支持）。 */
    public static final String DEFAULT_FORMAT = "mediawiki";

    private final WikiExportService exportService;
    private final TransferProperties properties;
    private final MessageSource messageSource;

    public ExportSpecialPage(WikiExportService exportService,
                             TransferProperties properties,
                             MessageSource messageSource) {
        this.exportService = exportService;
        this.properties = properties;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "导出页面";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.exportPage", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Export");
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    public String template() {
        return "special/export";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Map<String, String> parameters = request.parameters();
        String pagesText = Strings.trimToEmpty(parameters.get("pages"));
        String addns = Strings.trimToEmpty(parameters.get("addns"));
        if (!addns.isEmpty()) {
            List<String> titles = exportService.namespaceTitles(addns, request.user());
            pagesText = String.join("\n", titles);
            model.put("addedNamespaceCount", titles.size());
            model.put("addedNamespaceName", namespaceLabel(addns));
        }
        model.put("pagesText", pagesText);
        model.put("formatOptions", formatOptions(Strings.trimToEmpty(parameters.get("format"))));
        model.put("namespaceOptions", namespaceOptions(addns));
        model.put("selectedNamespace", addns);
        model.put("maxPages", properties.getExportMaxPages());
        model.put("maxRevisions", properties.getExportMaxRevisions());
    }

    private List<Map<String, Object>> formatOptions(String selected) {
        String value = selected.isEmpty() ? DEFAULT_FORMAT : selected;
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(option("mediawiki", message("export.format.mediawiki"), value));
        options.add(option("wikikernel-xml", message("export.format.wikikernelXml"), value));
        options.add(option("wikikernel-json", message("export.format.wikikernelJson"), value));
        return options;
    }

    private List<Map<String, Object>> namespaceOptions(String selected) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            options.add(option(namespace.id(), message(namespace.labelKey()), selected));
        }
        return options;
    }

    private Map<String, Object> option(String id, String label, String selected) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", id);
        option.put("label", label);
        option.put("selected", id.equalsIgnoreCase(selected));
        return option;
    }

    private String namespaceLabel(String id) {
        return WikiNamespaces.byId(id)
                .map(namespace -> message(namespace.labelKey()))
                .orElse(id);
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
