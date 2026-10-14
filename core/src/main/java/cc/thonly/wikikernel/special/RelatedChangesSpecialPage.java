package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PageLinkService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.web.WikiUrls;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:相关更改?page=标题} / {@code Special:RelatedChanges}：
 * 链接到该页面的其他页面上的最近更改（工具箱里的「相关更改」）。
 */
@Component
public class RelatedChangesSpecialPage implements SpecialPage {

    public static final String PARAM_PAGE = "page";

    private static final int LINK_LIMIT = 200;
    private static final int CHANGE_LIMIT = 50;

    private final PageLinkService pageLinkService;
    private final WikiPageMapper wikiPageMapper;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final UserService userService;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public RelatedChangesSpecialPage(PageLinkService pageLinkService,
                                     WikiPageMapper wikiPageMapper,
                                     WikiRevisionMapper wikiRevisionMapper,
                                     UserService userService,
                                     AccessControlService accessControlService,
                                     MessageSource messageSource) {
        this.pageLinkService = pageLinkService;
        this.wikiPageMapper = wikiPageMapper;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.userService = userService;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "相关更改";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.relatedChanges", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("RelatedChanges");
    }

    @Override
    public String template() {
        return "special/related-changes";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        String target = Strings.trimToEmpty(request.parameters().get(PARAM_PAGE));
        model.put("target", target);
        if (target.isEmpty()) {
            model.put("pageRequired", true);
            return;
        }

        List<Long> pageIds = pageLinkService.pagesLinkingTo(target, LINK_LIMIT).stream()
                .map(WikiPage::getId)
                .toList();
        if (pageIds.isEmpty()) {
            model.put("changes", List.of());
            return;
        }

        Map<Long, WikiPage> pageById = new LinkedHashMap<>();
        for (WikiPage page : wikiPageMapper.selectBatchIds(pageIds)) {
            pageById.put(page.getId(), page);
        }
        List<WikiRevision> revisions = accessControlService.filterReadable(
                wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                        .in(WikiRevision::getPageId, pageIds)
                        .eq(WikiRevision::getDeleted, false)
                        .orderByDesc(WikiRevision::getId)
                        .last("LIMIT " + CHANGE_LIMIT)),
                revision -> {
                    WikiPage page = pageById.get(revision.getPageId());
                    return page == null ? null : page.getTitle();
                },
                request.user());

        Set<Long> userIds = new LinkedHashSet<>();
        revisions.stream().map(WikiRevision::getUserId).filter(java.util.Objects::nonNull).forEach(userIds::add);
        Map<Long, String> userNames = userService.usernames(userIds);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiRevision revision : revisions) {
            WikiPage page = pageById.get(revision.getPageId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("page", page);
            row.put("pageUrl", page == null ? null : WikiUrls.pagePath(page.getTitle()));
            row.put("revision", revision);
            row.put("userName", revision.getUserId() == null ? null : userNames.get(revision.getUserId()));
            rows.add(row);
        }
        model.put("changes", rows);
    }
}
