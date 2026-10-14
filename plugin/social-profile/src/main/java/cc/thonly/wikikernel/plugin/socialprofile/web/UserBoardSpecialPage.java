package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserBoard;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import cc.thonly.wikikernel.plugin.socialprofile.service.FriendsService;
import cc.thonly.wikikernel.plugin.socialprofile.service.GiftService;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:留言板} / {@code Special:UserBoard}（插件特殊页，对应 MediaWiki SocialProfile 的
 * {@code Special:UserBoard}）：
 * <ul>
 *     <li>{@code ?user=用户名}：看某人的留言板（最近的留言 + 输入框；没有该用户时看自己的）；</li>
 *     <li>不带参数 / {@code ?user=} 自己：看自己的留言板；</li>
 *     <li>{@code ?all=1}：全站最近留言（新的在前，附带收件人）。</li>
 * </ul>
 * 留言正文按 wikitext 渲染（与页面正文同一套渲染器）；回复缩进显示；已删除的留言显示「已删除」占位。
 * 能不能留言由 {@code {BoardService}} 的规则判断（登录 / 不是本人 / 对方允许 / 未超长），
 * 真正的写入走 {@code POST /api/social-profile/board/post}。
 */
public class UserBoardSpecialPage extends SocialProfileSpecialPage implements SpecialPage {

    private final WikiText wikiText;

    public UserBoardSpecialPage(UserStore users, SocialProfileSupport support, ProfileService profiles,
                                BoardService board, FriendsService friends, GiftService gifts,
                                AvatarSupport avatars, WikiText wikiText) {
        super(users, support, profiles, board, friends, gifts, avatars);
        this.wikiText = wikiText;
    }

    @Override
    public String name() {
        return "留言板";
    }

    @Override
    public String displayName() {
        return display("socialProfile.board.title");
    }

    @Override
    public List<String> aliases() {
        return List.of("UserBoard", "Board", "MyBoard");
    }

    @Override
    public String category() {
        return SpecialPageCategories.USERS;
    }

    @Override
    public String template() {
        return "special/user-board";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        // 留言板是只读可公开的页面：匿名用户也能看，只是不能留言（输入框位置显示登录提示）
        if (!prepare(request, model, false)) {
            return;
        }
        // 本页的「留言板」标签地址（看别人的板时带上 ?user=）
        String requestedUser = Strings.trimToEmpty(request.parameters().get("user"));
        model.put("boardSelfOrTargetUrl", SocialProfileSupport.pagePath(requestedUser.isEmpty()
                ? "特殊:留言板"
                : "特殊:留言板?user=" + SocialProfileSupport.encodeQuery(requestedUser)));
        model.put("boardAllUrl", SocialProfileSupport.pagePath("特殊:留言板?all=1"));
        if ("1".equals(Strings.trimToEmpty(request.parameters().get("all")))) {
            populateAll(model);
            return;
        }
        model.put("boardAll", false);
        model.put("boardNoTarget", false);
        model.put("boardCanPost", false);
        boolean anonymous = request == null || request.user() == null;
        boolean askedUser = !Strings.trimToEmpty(request == null ? null
                : request.parameters().get("user")).isEmpty();
        if (anonymous && !askedUser) {
            // 匿名且没指定用户：没有「自己的留言板」可看——模板只给登录提示 + 公开入口
            model.put("boardNoTarget", true);
            model.put("boardRows", List.of());
            model.put("boardReplyRows", Map.of());
            return;
        }
        UserStore.UserRef target = target(request, model, true);
        if (target == null) {
            model.put("boardTargetNotFound", Boolean.TRUE.equals(model.get("targetNotFound")));
            model.put("boardRows", List.of());
            model.put("boardReplyRows", Map.of());
            return;
        }
        long viewerId = currentUserId(request);
        boolean self = viewerId == target.id();
        boolean allowBoard = board.allowsBoard(target.id());
        model.put("boardSelf", self);
        model.put("boardAllowBoard", allowBoard);
        model.put("boardCanPost", !self && allowBoard
                && (viewerId > 0 || board.allowsAnonymous()));
        model.put("boardTotal", board.count(target.id()));
        model.put("boardEditProfileUrl", self ? SocialProfileSupport.pagePath("特殊:编辑资料") : null);
        model.put("boardFriendsUrl", SocialProfileSupport.pagePath("特殊:好友"
                + (self ? "" : "?user=" + SocialProfileSupport.encodeQuery(target.username()))));

        List<WikiUserBoard> rows = board.recent(target.id(), board.pageLimit());
        Set<Long> authorIds = new LinkedHashSet<>();
        for (WikiUserBoard row : rows) {
            if (row.getFromUserId() != null) {
                authorIds.add(row.getFromUserId());
            }
        }
        Map<Long, String> names = users == null ? Map.of() : users.names(authorIds);
        // 最多展示一层回复：父留言是这一页里的顶层留言时，把它下面的回复合并进来
        Map<Long, List<WikiUserBoard>> replies = new LinkedHashMap<>();
        for (WikiUserBoard row : rows) {
            if (row.getParentId() != null) {
                continue;
            }
            List<WikiUserBoard> children = board.replies(row.getId(), board.pageLimit());
            if (!children.isEmpty()) {
                replies.put(row.getId(), children);
            }
        }
        model.put("boardRows", toRows(rows.stream().filter(row -> row.getParentId() == null).toList(),
                names, viewerId, target.id(), names));
        Map<Long, List<Map<String, Object>>> replyRows = new LinkedHashMap<>();
        for (Map.Entry<Long, List<WikiUserBoard>> entry : replies.entrySet()) {
            Set<Long> replyAuthors = new LinkedHashSet<>(authorIds);
            for (WikiUserBoard child : entry.getValue()) {
                if (child.getFromUserId() != null) {
                    replyAuthors.add(child.getFromUserId());
                }
            }
            Map<Long, String> replyNames = users == null ? Map.of() : users.names(replyAuthors);
            replyRows.put(entry.getKey(), toRows(entry.getValue(), replyNames, viewerId, target.id(), names));
        }
        model.put("boardReplyRows", replyRows);
    }

    /** 全站最近留言（{@code ?all=1}）。 */
    private void populateAll(Map<String, Object> model) {
        model.put("boardAll", true);
        model.put("boardNoTarget", false);
        model.put("boardAllUrl", SocialProfileSupport.pagePath("特殊:留言板?all=1"));
        model.put("boardCanPost", false);
        List<WikiUserBoard> rows = board.recentAll(board.pageLimit());
        Set<Long> ids = new LinkedHashSet<>();
        for (WikiUserBoard row : rows) {
            if (row.getFromUserId() != null) {
                ids.add(row.getFromUserId());
            }
            if (row.getToUserId() != null) {
                ids.add(row.getToUserId());
            }
        }
        Map<Long, String> names = users == null ? Map.of() : users.names(ids);
        model.put("boardRows", toRows(rows, names, 0, 0, names));
        model.put("boardReplyRows", Map.of());
    }

    /** 留言行 → 模板模型（正文在这里渲染成 HTML，作者 / 时间 / 权限标记一并给出）。 */
    private List<Map<String, Object>> toRows(List<WikiUserBoard> rows, Map<Long, String> names,
                                             long viewerId, long boardOwnerId, Map<Long, String> allNames) {
        List<Map<String, Object>> result = new ArrayList<>();
        String pattern = support.message("format.dateTime", "yyyy-MM-dd HH:mm");
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException exception) {
            formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        }
        for (WikiUserBoard row : rows) {
            Long authorId = row.getFromUserId();
            String author = authorId == null ? "" : names.getOrDefault(authorId, "#" + authorId);
            Long toId = row.getToUserId();
            String toUser = toId == null ? "" : allNames.getOrDefault(toId, "#" + toId);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", row.getId());
            view.put("parentId", row.getParentId());
            view.put("author", author.isEmpty() ? "#" + authorId : author);
            view.put("authorUrl", SocialProfileSupport.pagePath("用户:" + author));
            view.put("toUser", toUser);
            view.put("timeText", row.getCreateAt() == null ? "" : row.getCreateAt().format(formatter));
            view.put("deleted", Boolean.TRUE.equals(row.getDeleted()));
            view.put("canDelete", viewerId > 0
                    && (viewerId == boardOwnerId || (authorId != null && authorId == viewerId)));
            String content = Strings.trimToEmpty(row.getContent());
            view.put("contentHtml", content.isEmpty() ? ""
                    : (wikiText == null ? SocialProfileSupport.escape(content)
                    : wikiText.toHtml(content, "特殊:留言板")));
            result.add(view);
        }
        return result;
    }
}
