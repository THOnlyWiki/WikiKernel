package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.impl.PageReportQueries;
import cc.thonly.wikikernel.wikitext.TrackingCategories;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TrackingCategoriesSpecialPage extends PageListReport {
    private final AccessControlService access;

    TrackingCategoriesSpecialPage(MessageSource messages, AccessControlService access) {
        super(messages);
        this.access = access;
    }

    @Override
    public String name() {
        return "追踪分类";
    }

    @Override
    public List<String> aliases() {
        return List.of("TrackingCategories", "跟踪分类", "追蹤分類", "追蹤類別");
    }

    @Override
    protected String messageKey() {
        return "trackingCategories";
    }

    @Override
    protected String body(SpecialPageRequest request) {
        String key = TrackingCategories.HIDDEN_CATEGORY;
        String category = TrackingCategories.categoryTitle(messageSource, key);
        String interfacePage = "WikiKernel:" + key;
        boolean readable = category.isEmpty() || access.canRead(category, request.user());
        String messageLink = access.canRead(interfacePage, request.user()) ? link(interfacePage, key) : escape(key);
        String categoryCell = category.isEmpty() ? escape(message("report.trackingCategories.disabled"))
                : link(category, PageReportQueries.bareTitle(category));
        List<List<String>> rows = readable
                ? List.of(List.of(categoryCell, messageLink, escape(message(key + "-desc"))))
                : List.of();
        return intro() + table(List.of(message("report.trackingCategories.category"),
                message("report.trackingCategories.message"), message("report.trackingCategories.description")), rows);
    }
}
