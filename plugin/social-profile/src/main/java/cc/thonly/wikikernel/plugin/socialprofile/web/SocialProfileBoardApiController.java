package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiUserBoard;
import cc.thonly.wikikernel.plugin.socialprofile.service.BoardService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户留言板 API（插件自己的 {@code @RestController}）：
 * <ul>
 *     <li>{@code POST /api/social-profile/board/post}：留言（{@code to} 用户名，{@code content} wikitext，
 *     可选 {@code parent} 父留言 id = 回复）；</li>
 *     <li>{@code POST /api/social-profile/board/delete}：删除留言（板主 / 作者 / 管理员，软删除）。</li>
 * </ul>
 * 鉴权：默认需要登录（匿名留言由 {@code socialProfile.board.allowAnonymous} 打开，默认关闭）；
 * 被封禁 403；限流分类 {@code social-profile-board}。
 */
@RestController
@RequestMapping("/api/social-profile/board")
public class SocialProfileBoardApiController extends SocialProfileApiSupport {

    private final BoardService board;

    public SocialProfileBoardApiController(BoardService board, UserStore users, AccessCheck access,
                                           CurrentUser currentUser, SocialProfileSupport support) {
        super(access, currentUser, support, users);
        this.board = board;
    }

    /** 留言 / 回复。 */
    @PostMapping("/post")
    public Map<String, Object> post(@RequestParam("to") String to,
                                    @RequestParam("content") String content,
                                    @RequestParam(value = "parent", required = false) String parent) {
        Map<String, Object> closed = gate();
        if (closed != null) {
            return closed;
        }
        // 匿名：默认不允许（与 SocialProfile 的 $wgSocialProfileAllowAnonBoardWrites 对应）
        if (!loggedIn() && !board.allowsAnonymous()) {
            return error("permissions.loginRequired");
        }
        Map<String, Object> blocked = blockGate();
        if (blocked != null) {
            return blocked;
        }
        long toUserId = userIdOf(to);
        if (toUserId <= 0) {
            return error("socialProfile.error.userNotFound");
        }
        try {
            Long parentId = parseId(parent);
            WikiUserBoard saved = parentId == null
                    ? board.post(userId(), toUserId, content)
                    : board.reply(userId(), parentId, content);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("message", boardRow(saved));
            return ok("socialProfile.board.posted", data);
        } catch (BoardService.BoardException exception) {
            return fail(exception);
        }
    }

    /** 删除留言（软删除）。 */
    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestParam("id") long id) {
        Map<String, Object> closed = gateLoggedIn();
        if (closed != null) {
            return closed;
        }
        try {
            boolean deleted = board.delete(userId(), id, isAdmin());
            if (!deleted) {
                return error("socialProfile.board.alreadyDeleted");
            }
            return ok("socialProfile.board.deleted", null);
        } catch (BoardService.BoardException exception) {
            return fail(exception);
        }
    }

    /** 可选的父留言 id（空 / 非数字 = 顶层留言）。 */
    private static Long parseId(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            long id = Long.parseLong(text);
            return id > 0 ? id : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
