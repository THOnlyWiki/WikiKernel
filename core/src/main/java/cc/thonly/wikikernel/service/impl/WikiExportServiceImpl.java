package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.config.SpecialPageProperties;
import cc.thonly.wikikernel.config.TransferProperties;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PageLinkService;
import cc.thonly.wikikernel.service.SiteNameService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiExportService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.transfer.ExportData;
import cc.thonly.wikikernel.transfer.ExportPageData;
import cc.thonly.wikikernel.web.WikiNamespaces;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 导出实现：收集页面 + 版本（查看权限过滤、限额截断、模板递归），
 * 序列化交给 {@code transfer} 包里的三个导出器。
 */
@Service
public class WikiExportServiceImpl implements WikiExportService {

    /** 「添加命名空间全部页面」最多扫描的页面行数（命名空间 / 权限过滤在内存里做）。 */
    private static final int NAMESPACE_SCAN_LIMIT = 5000;

    /** 导出文件的生成器标识。 */
    private static final String GENERATOR = "WikiKernel";

    private final WikiPageService wikiPageService;
    private final WikiPageMapper wikiPageMapper;
    private final AccessControlService accessControlService;
    private final PageLinkService pageLinkService;
    private final UserService userService;
    private final SpecialPageProperties specialPageProperties;
    private final TransferProperties properties;
    private final SiteNameService siteNameService;

    public WikiExportServiceImpl(WikiPageService wikiPageService,
                                 WikiPageMapper wikiPageMapper,
                                 AccessControlService accessControlService,
                                 PageLinkService pageLinkService,
                                 UserService userService,
                                 SpecialPageProperties specialPageProperties,
                                 TransferProperties properties,
                                 SiteNameService siteNameService) {
        this.wikiPageService = wikiPageService;
        this.wikiPageMapper = wikiPageMapper;
        this.accessControlService = accessControlService;
        this.pageLinkService = pageLinkService;
        this.userService = userService;
        this.specialPageProperties = specialPageProperties;
        this.properties = properties;
        this.siteNameService = siteNameService;
    }

    @Override
    public ExportData collect(List<String> titles, boolean history, boolean templates,
                              WikiUser user, String baseUrl) {
        int pageBudget = Math.max(1, properties.getExportMaxPages());
        int revisionBudget = Math.max(1, properties.getExportMaxRevisions());
        boolean truncated = false;

        Map<String, ExportPageData> collected = new LinkedHashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        if (titles != null) {
            for (String title : titles) {
                String normalized = title == null ? "" : title.trim();
                if (!normalized.isEmpty()) {
                    queue.add(normalized);
                }
            }
        }

        while (!queue.isEmpty()) {
            if (collected.size() >= pageBudget) {
                truncated = true;
                break;
            }
            String title = queue.poll();
            if (!visited.add(title)) {
                continue;
            }
            // 特殊页不能导出；无权查看的页面直接跳过（不泄露标题）
            if (isSpecialPageTitle(title)
                    || !accessControlService.canRead(title, user)) {
                continue;
            }
            WikiPage page = wikiPageService.getVisibleByTitle(title).orElse(null);
            if (page == null) {
                continue;
            }
            List<WikiRevision> revisions = new ArrayList<>(wikiPageService.getHistory(page.getId()));
            revisions.sort(Comparator.comparing(WikiRevision::getId));
            if (!history) {
                revisions = revisions.stream()
                        .filter(revision -> !Boolean.TRUE.equals(revision.getDeleted()))
                        .reduce((first, second) -> second)
                        .map(List::of)
                        .orElse(List.of());
            }
            if (revisions.isEmpty()) {
                continue;
            }
            if (revisionBudget <= 0) {
                truncated = true;
                break;
            }
            if (revisions.size() > revisionBudget) {
                revisions = new ArrayList<>(revisions.subList(revisions.size() - revisionBudget, revisions.size()));
                truncated = true;
            }
            revisionBudget -= revisions.size();
            collected.put(title, new ExportPageData(page, List.copyOf(revisions)));

            if (templates) {
                for (String template : pageLinkService.templatesUsedBy(title)) {
                    if (template != null && !template.isBlank() && !visited.contains(template)) {
                        queue.add(template);
                    }
                }
            }
        }

        List<Long> userIds = collected.values().stream()
                .flatMap(pageData -> pageData.revisions().stream())
                .map(WikiRevision::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of() : userService.usernames(userIds);

        return new ExportData(siteNameService.name(), baseUrl == null ? "" : baseUrl, GENERATOR,
                LocalDateTime.now(), truncated, List.copyOf(collected.values()), userNames);
    }

    @Override
    public List<String> namespaceTitles(String namespaceId, WikiUser user) {
        Optional<WikiNamespaces.Namespace> namespace = WikiNamespaces.byId(namespaceId);
        if (namespace.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, properties.getExportMaxPages());
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderByAsc(WikiPage::getTitle)
                .last("LIMIT " + NAMESPACE_SCAN_LIMIT));
        List<String> titles = new ArrayList<>();
        for (WikiPage page : pages) {
            if (titles.size() >= limit) {
                break;
            }
            String title = page.getTitle();
            if (!WikiNamespaces.matches(title, namespace.get())) {
                continue;
            }
            if (isSpecialPageTitle(title)
                    || !accessControlService.canRead(title, user)) {
                continue;
            }
            titles.add(title);
        }
        return titles;
    }

    /** 标题是否带任一特殊页前缀（{@code 特殊:} / {@code Special:} 等，配置见 wikikernel.special-page.prefixes）。 */
    private boolean isSpecialPageTitle(String title) {
        String normalized = title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
        for (String prefix : specialPageProperties.getPrefixes()) {
            if (prefix != null && !prefix.isBlank()
                    && normalized.startsWith(prefix.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
