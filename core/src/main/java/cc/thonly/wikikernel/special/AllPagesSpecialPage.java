package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.wikitext.WikiRedirect;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:全部页面} / {@code Special:AllPages}：仿 MediaWiki 的全部页面浏览。
 * <p>
 * 支持：命名空间筛选、标题范围（显示页面开始于 / 结束于，结束标题包含在结果里）、隐藏重定向、
 * 标题升序、每 {@link #PAGE_SIZE} 条一页并给出「上一页（标题）/ 下一页（标题）」链接；
 * 列表为三列布局（模板 / CSS），不显示更新时间。
 * 无权查看的页面不出现（标题也不泄露）。
 */
@Component
public class AllPagesSpecialPage implements SpecialPage {

    /** 每页显示条数（翻页块大小）。 */
    private static final int PAGE_SIZE = 100;

    /** 单次最多扫描多少行（命名空间 / 重定向过滤在内存里做）。 */
    private static final int MAX_SCAN = 2000;

    private final WikiPageMapper wikiPageMapper;
    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public AllPagesSpecialPage(WikiPageMapper wikiPageMapper,
                               WikiPageService wikiPageService,
                               AccessControlService accessControlService,
                               MessageSource messageSource) {
        this.wikiPageMapper = wikiPageMapper;
        this.wikiPageService = wikiPageService;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "全部页面";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.allPages", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        // AllPages = MediaWiki 的英文名；「所有页面」= MediaWiki 中文界面的别名（侧栏页面里常写 Special:所有页面）
        return List.of("AllPages", "所有页面");
    }

    @Override
    public String template() {
        return "special/all-pages";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiNamespaces.Namespace namespace = WikiNamespaces
                .byId(request.parameters().get("namespace")).orElse(null);
        String from = Strings.trimToEmpty(request.parameters().get("from")).trim();
        String to = Strings.trimToEmpty(request.parameters().get("to")).trim();
        boolean hideRedirects = isOn(request.parameters().get("hideredirects"));
        WikiUser user = request.user();

        List<String> forward = matchingTitles(forwardQuery(from, to), namespace, hideRedirects, user);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String title : forward.subList(0, Math.min(PAGE_SIZE, forward.size()))) {
            rows.add(row(title));
        }
        String nextFrom = forward.size() > PAGE_SIZE ? forward.get(PAGE_SIZE) : null;

        String prevFrom = null;
        if (!from.isEmpty()) {
            List<String> backward = matchingTitles(backwardQuery(from), namespace, hideRedirects, user);
            if (!backward.isEmpty()) {
                // backward 是标题倒序：上一块的第一条 = 倒序里第 PAGE_SIZE 条（不足则取最早的一条）
                prevFrom = backward.get(Math.min(PAGE_SIZE, backward.size()) - 1);
            }
        }

        model.put("pages", rows);
        model.put("fromValue", from);
        model.put("toValue", to);
        model.put("hideRedirects", hideRedirects);
        model.put("prevFrom", prevFrom);
        model.put("nextFrom", nextFrom);
        model.put("selectedNamespace", namespace == null ? "all" : namespace.id());
        model.put("namespaceOptions", namespaceOptions(namespace));
    }

    /** 当前范围（from ≤ 标题 ≤ to）内、未隐藏未删除的页面，按标题升序。 */
    private List<WikiPage> forwardQuery(String from, String to) {
        return wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getHided, false)
                .eq(WikiPage::getDeleted, false)
                .ge(!from.isEmpty(), WikiPage::getTitle, from)
                .le(!to.isEmpty(), WikiPage::getTitle, to)
                .orderByAsc(WikiPage::getTitle)
                .last("LIMIT " + MAX_SCAN));
    }

    /** 指定标题之前的页面（算上一页边界），按标题倒序。 */
    private List<WikiPage> backwardQuery(String before) {
        return wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getHided, false)
                .eq(WikiPage::getDeleted, false)
                .lt(WikiPage::getTitle, before)
                .orderByDesc(WikiPage::getTitle)
                .last("LIMIT " + MAX_SCAN));
    }

    /** 过滤：查看权限 → 命名空间 → 隐藏重定向；保持传入顺序。 */
    private List<String> matchingTitles(List<WikiPage> pages, WikiNamespaces.Namespace namespace,
                                        boolean hideRedirects, WikiUser user) {
        List<String> titles = new ArrayList<>();
        for (WikiPage page : accessControlService.filterReadable(pages, WikiPage::getTitle, user)) {
            if (!WikiNamespaces.matches(page.getTitle(), namespace)) {
                continue;
            }
            if (hideRedirects && isRedirect(page)) {
                continue;
            }
            titles.add(page.getTitle());
        }
        return titles;
    }

    /** 页面最新内容是否是 {@code #REDIRECT}（隐藏重定向用）。 */
    private boolean isRedirect(WikiPage page) {
        return WikiRedirect.targetOf(wikiPageService.getLatestContent(page.getId()).orElse(""))
                .isPresent();
    }

    /** 用 Map 是为了让模板既能拿标题，也能拿逐段编码好的 URL（含子页面 /）。 */
    private Map<String, Object> row(String title) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("url", WikiUrls.pagePath(title));
        return row;
    }

    private List<Map<String, Object>> namespaceOptions(WikiNamespaces.Namespace selected) {
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(option("all", message("allPages.allNamespaces"), selected == null));
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

    private static boolean isOn(String value) {
        return "1".equals(value) || "on".equalsIgnoreCase(value)
                || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
