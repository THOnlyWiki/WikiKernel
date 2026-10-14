package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.web.WikiUrls;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code 特殊:用户贡献?name=用户名} / {@code Special:Contributions}：
 * 某用户的编辑贡献列表（工具箱里的「用户贡献」）。
 */
@Component
public class UserContributionsSpecialPage implements SpecialPage {

    public static final String PARAM_NAME = "name";

    private static final int LIMIT = 100;

    private final UserService userService;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final WikiPageMapper wikiPageMapper;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public UserContributionsSpecialPage(UserService userService,
                                        WikiRevisionMapper wikiRevisionMapper,
                                        WikiPageMapper wikiPageMapper,
                                        AccessControlService accessControlService,
                                        MessageSource messageSource) {
        this.userService = userService;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.wikiPageMapper = wikiPageMapper;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "用户贡献";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.userContributions", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Contributions", "UserContributions");
    }

    @Override
    public String template() {
        return "special/user-contributions";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        String username = Strings.trimToEmpty(request.parameters().get(PARAM_NAME));
        Optional<WikiUser> found = userService.findByUsername(username);
        if (found.isEmpty()) {
            model.put("userNotFound", true);
            return;
        }
        WikiUser user = found.get();

        List<WikiRevision> revisions = wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getUserId, user.getId())
                .orderByDesc(WikiRevision::getId)
                .last("LIMIT " + LIMIT));

        Map<Long, WikiPage> pageById = new LinkedHashMap<>();
        List<Long> pageIds = revisions.stream().map(WikiRevision::getPageId).distinct().toList();
        if (!pageIds.isEmpty()) {
            for (WikiPage page : wikiPageMapper.selectBatchIds(pageIds)) {
                pageById.put(page.getId(), page);
            }
        }

        // 看不到的页面不在贡献列表里出现（标题也不泄露）
        List<WikiRevision> visibleRevisions = accessControlService.filterReadable(revisions,
                revision -> {
                    WikiPage page = pageById.get(revision.getPageId());
                    return page == null ? null : page.getTitle();
                },
                request.user());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiRevision revision : visibleRevisions) {
            WikiPage page = pageById.get(revision.getPageId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("page", page);
            row.put("revision", revision);
            if (page != null) {
                String pageUrl = WikiUrls.pagePath(page.getTitle());
                row.put("pageUrl", pageUrl);
                row.put("oldUrl", pageUrl + "?oldid=" + revision.getId());
                row.put("diffUrl", pageUrl + "?diff=prev&oldid=" + revision.getId());
                row.put("historyUrl", pageUrl + "?action=history");
                // 对历史版本直接编辑：编辑器载入该版本，保存后生成新版本
                row.put("editUrl", pageUrl + "?action=edit&oldid=" + revision.getId());
                row.put("current", revision.getId().equals(page.getLatestRevisionId()));
            }
            rows.add(row);
        }
        model.put("user", user);
        model.put("rows", rows);
        model.put("title", user.getUsername());
    }
}
