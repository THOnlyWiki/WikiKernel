package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiMessage;
import cc.thonly.wikikernel.plugin.socialprofile.service.MessageCenter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 私信 API（插件自己的 {@code @RestController}，由 core 动态挂载）：
 * 发送 {@code POST /api/messages/send}、删除自己一侧 {@code POST /api/messages/delete}。
 * <p>
 * 与迁移前 core 的 {@code MessageApiController} 语义一致：需要登录（未登录 401）、被封禁 403、
 * 功能关闭 403、收件人不存在 / 发给自己 400（消息键 {@code messages.userNotFound} /
 * {@code messages.selfNotAllowed}）、无关用户删除 404。
 */
@RestController
@RequestMapping("/api/messages")
public class MessagesApiController {

    private final MessageCenter messages;
    private final AccessCheck access;
    private final CurrentUser currentUser;
    private final SocialProfileSupport support;

    public MessagesApiController(MessageCenter messages, AccessCheck access, CurrentUser currentUser,
                                 SocialProfileSupport support) {
        this.messages = messages;
        this.access = access;
        this.currentUser = currentUser;
        this.support = support;
    }

    /** 发送私信（wikitext 内容）。 */
    @PostMapping("/send")
    public AjaxResult<Map<String, Object>> send(@RequestParam("to") String to,
                                                @RequestParam("content") String content) {
        if (!support.enabled()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN,
                    support.message("feature.userInteractionDisabled"));
        }
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED,
                    support.message("permissions.loginRequired"));
        }
        Optional<String> block = access.blockNotice(viewer);
        if (block.isPresent()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, block.get());
        }
        String recipient = Strings.trimToEmpty(to);
        if (recipient.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("messages.recipientRequired"));
        }
        String text = Strings.trimToEmpty(content);
        if (text.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("messages.empty"));
        }
        try {
            WikiMessage message = messages.send(viewer.id(), recipient, text);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", message.getId());
            return AjaxResult.success(support.message("messages.sent"), data);
        } catch (IllegalArgumentException exception) {
            String key = Strings.trimToEmpty(exception.getMessage());
            return AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message(key.isEmpty() ? "messages.notFound" : key));
        }
    }

    /** 删除私信（自己一侧的软删除）。 */
    @PostMapping("/delete")
    public AjaxResult<Void> delete(@RequestParam("id") long id) {
        if (!support.enabled()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN,
                    support.message("feature.userInteractionDisabled"));
        }
        Viewer viewer = currentUser.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED,
                    support.message("permissions.loginRequired"));
        }
        if (!messages.delete(viewer.id(), id)) {
            return AjaxResult.error(AjaxCode.NOT_FOUND,
                    support.message("messages.notFound"));
        }
        return AjaxResult.success(support.message("messages.deleted"), null);
    }
}
