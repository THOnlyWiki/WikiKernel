package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.common.web.WikiUrls;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code 特殊:随机页面} / {@code Special:Random}：302 跳转到一个随机页面（仿 MediaWiki）。
 * <p>
 * 默认在主命名空间内随机；{@code ?namespace=<命名空间id>} 指定命名空间（如 {@code 模板}），
 * {@code ?namespace=all} 表示全部命名空间。只会在**当前用户有权查看**的页面里随机。
 */
@Component
public class RandomPageSpecialPage implements SpecialPage {

    /** 单次最多扫描多少页面（与全部页面一致）。 */
    private static final int MAX_SCAN = 2000;

    private final WikiPageMapper wikiPageMapper;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public RandomPageSpecialPage(WikiPageMapper wikiPageMapper,
                                 AccessControlService accessControlService,
                                 MessageSource messageSource) {
        this.wikiPageMapper = wikiPageMapper;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "随机页面";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.randomPage", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Random", "RandomPage", "随机条目");
    }

    @Override
    public String template() {
        return "special/random-page";
    }

    @Override
    public String category() {
        return SpecialPageCategories.REDIRECTS;
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        String namespaceParam = Strings.trimToEmpty(request.parameters().get("namespace"));
        WikiNamespaces.Namespace namespace;
        if ("all".equalsIgnoreCase(namespaceParam)) {
            namespace = null;
        } else if (namespaceParam.isEmpty()) {
            // 与 MediaWiki 一致：默认主命名空间
            namespace = WikiNamespaces.byId("main").orElse(null);
        } else {
            namespace = WikiNamespaces.byId(namespaceParam)
                    .orElseGet(() -> WikiNamespaces.byId("main").orElse(null));
        }

        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderByAsc(WikiPage::getId)
                .last("LIMIT " + MAX_SCAN));
        List<WikiPage> candidates = new ArrayList<>();
        for (WikiPage page : accessControlService.filterReadable(pages, WikiPage::getTitle, request.user())) {
            if (WikiNamespaces.matches(page.getTitle(), namespace)) {
                candidates.add(page);
            }
        }
        if (candidates.isEmpty()) {
            model.put("noPages", true);
            return;
        }
        WikiPage pick = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        model.put(REDIRECT_MODEL_ATTRIBUTE, WikiUrls.pagePath(pick.getTitle()));
    }
}
