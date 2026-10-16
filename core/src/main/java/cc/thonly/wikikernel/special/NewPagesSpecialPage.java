package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.common.web.WikiUrls;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code 特殊:最新页面} / {@code Special:NewPages}：最近创建的页面（仿 MediaWiki）。
 * <p>
 * 每行：创建时间、页面（差异 | 历史）、创建者（用户页链接）、创建版本大小、创建摘要；
 * 支持命名空间与条数筛选（{@code ?namespace=模板&limit=100}，条数 50 / 100 / 250 / 500）。
 * 无权查看的页面不出现（标题也不泄露）。
 */
@Component
public class NewPagesSpecialPage implements SpecialPage {

    private static final int DEFAULT_LIMIT = 50;
    private static final List<Integer> LIMIT_OPTIONS = List.of(50, 100, 250, 500);

    /** 先按 条数 × 该系数 取页面，再按查看权限 / 命名空间过滤，尽量凑满一页。 */
    private static final int SCAN_FACTOR = 5;

    private final WikiPageMapper wikiPageMapper;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final UserService userService;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public NewPagesSpecialPage(WikiPageMapper wikiPageMapper,
                               WikiRevisionMapper wikiRevisionMapper,
                               UserService userService,
                               AccessControlService accessControlService,
                               MessageSource messageSource) {
        this.wikiPageMapper = wikiPageMapper;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.userService = userService;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "最新页面";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.newPages", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("NewPages", "Newpages");
    }

    @Override
    public String template() {
        return "special/new-pages";
    }

    @Override
    public String category() {
        return SpecialPageCategories.CHANGES;
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        Map<String, String> parameters = request.parameters();
        int limit = parseChoice(parameters.get("limit"));
        WikiNamespaces.Namespace namespace = WikiNamespaces
                .byId(parameters.get("namespace")).orElse(null);
        WikiUser user = request.user();

        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                        .eq(WikiPage::getDeleted, false)
                        .eq(WikiPage::getHided, false)
                        .isNotNull(WikiPage::getCreateAt)
                        .orderByDesc(WikiPage::getCreateAt)
                        .orderByDesc(WikiPage::getId)
                        .last("LIMIT " + (limit * SCAN_FACTOR)))
                .stream()
                .filter(page -> WikiNamespaces.matches(page.getTitle(), namespace))
                .toList();
        pages = accessControlService.filterReadable(pages, WikiPage::getTitle, user)
                .stream().limit(limit).toList();

        // 每个页面的创建版本（用于创建者 / 大小 / 摘要 / 差异链接）
        Map<Long, WikiRevision> firstByPage = new LinkedHashMap<>();
        if (!pages.isEmpty()) {
            List<Long> pageIds = pages.stream().map(WikiPage::getId).toList();
            wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                            .in(WikiRevision::getPageId, pageIds)
                            .orderByAsc(WikiRevision::getId))
                    .forEach(revision -> firstByPage.putIfAbsent(revision.getPageId(), revision));
        }
        Map<Long, String> names = userService.usernames(firstByPage.values().stream()
                .map(WikiRevision::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiPage page : pages) {
            WikiRevision first = firstByPage.get(page.getId());
            String pageUrl = WikiUrls.pagePath(page.getTitle());
            String userName = first == null || first.getUserId() == null
                    ? null : names.get(first.getUserId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", page.getTitle());
            row.put("url", pageUrl);
            row.put("time", page.getCreateAt() != null ? page.getCreateAt()
                    : (first == null ? null : first.getCreateAt()));
            row.put("userName", userName);
            row.put("userUrl", userName == null ? null : WikiUrls.pagePath("用户:" + userName));
            row.put("bytes", first == null || first.getContent() == null ? 0
                    : first.getContent().getBytes(StandardCharsets.UTF_8).length);
            row.put("comment", first == null ? "" : Strings.trimToEmpty(first.getComment()));
            row.put("diffUrl", first == null ? pageUrl : pageUrl + "?diff=prev&oldid=" + first.getId());
            row.put("historyUrl", pageUrl + "?action=history");
            rows.add(row);
        }

        model.put("rows", rows);
        model.put("selectedLimit", limit);
        model.put("limitOptions", LIMIT_OPTIONS);
        model.put("selectedNamespace", namespace == null ? "all" : namespace.id());
        model.put("namespaceOptions", namespaceOptions(namespace));
    }

    private List<Map<String, Object>> namespaceOptions(WikiNamespaces.Namespace selected) {
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(option("all", message("newPages.allNamespaces"), selected == null));
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            options.add(option(namespace.id(), message(namespace.labelKey()),
                    selected != null && selected.equals(namespace)));
        }
        return options;
    }

    private static Map<String, Object> option(String id, String label, boolean selected) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", id);
        option.put("label", label);
        option.put("selected", selected);
        return option;
    }

    private static int parseChoice(String raw) {
        try {
            int value = Integer.parseInt(Strings.trimToEmpty(raw));
            return LIMIT_OPTIONS.contains(value) ? value : DEFAULT_LIMIT;
        } catch (NumberFormatException exception) {
            return DEFAULT_LIMIT;
        }
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
