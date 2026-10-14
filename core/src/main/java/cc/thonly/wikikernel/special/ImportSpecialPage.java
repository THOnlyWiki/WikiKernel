package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.config.TransferProperties;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.web.WikiNamespaces;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:导入页面} / {@code Special:Import}（仿 MediaWiki Special:Import）：
 * 上传 MediaWiki XML / WikiKernel XML / WikiKernel JSON（自动识别），可指定导入到哪个命名空间。
 * 只有管理员 / 行政员可用（与滥用过滤器管理页同一档）。
 */
@Component
public class ImportSpecialPage implements SpecialPage {

    /** 导入页「保持原命名空间」的下拉值（空串）。 */
    public static final String DEFAULT_NAMESPACE = "";

    private final PermissionService permissionService;
    private final TransferProperties properties;
    private final MessageSource messageSource;

    public ImportSpecialPage(PermissionService permissionService,
                             TransferProperties properties,
                             MessageSource messageSource) {
        this.permissionService = permissionService;
        this.properties = properties;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "导入页面";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.importPage", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Import");
    }

    @Override
    public String category() {
        return SpecialPageCategories.WIKI;
    }

    @Override
    public String template() {
        return "special/import";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        if (user == null || !permissionService.canManage(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message("import.forbidden"));
        }
        model.put("namespaceOptions", namespaceOptions());
        model.put("maxPages", properties.getImportMaxPages());
        model.put("maxRevisions", properties.getImportMaxRevisions());
        model.put("maxMegabytes", Math.max(1, properties.getImportMaxBytes() / (1024 * 1024)));
    }

    private List<Map<String, Object>> namespaceOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(option("", message("import.namespaceKeep")));
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            options.add(option(namespace.id(), message(namespace.labelKey())));
        }
        return options;
    }

    private Map<String, Object> option(String id, String label) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", id);
        option.put("label", label);
        return option;
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
