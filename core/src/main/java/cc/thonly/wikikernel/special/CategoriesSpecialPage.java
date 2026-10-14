package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.PageCategoryService;
import cc.thonly.wikikernel.web.WikiUrls;
import cc.thonly.wikikernel.wikitext.WikiLinkScanner;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:分类列表} / {@code Special:Categories}：本 wiki 的全部分类与成员数（读分类索引表）。
 */
@Component
public class CategoriesSpecialPage implements SpecialPage {

    private static final int LIMIT = 500;

    private final PageCategoryService pageCategoryService;
    private final MessageSource messageSource;

    public CategoriesSpecialPage(PageCategoryService pageCategoryService, MessageSource messageSource) {
        this.pageCategoryService = pageCategoryService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "分类列表";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.categories", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Categories");
    }

    @Override
    public String template() {
        return "special/categories";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        model.put("categories", pageCategoryService.allCategories(LIMIT).stream()
                .map(category -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("title", category.title());
                    row.put("label", WikiLinkScanner.categoryName(category.title()));
                    row.put("url", WikiUrls.pagePath(category.title()));
                    row.put("count", category.count());
                    return row;
                })
                .toList());
    }
}
