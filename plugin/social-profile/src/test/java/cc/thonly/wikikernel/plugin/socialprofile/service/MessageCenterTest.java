package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileFakes;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiMessage;
import cc.thonly.wikikernel.plugin.socialprofile.web.MessagesApiController;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 私信规则：发送（收件人存在 / 不能发给自己 / 内容非空）、收件箱 / 发件箱、
 * 阅读自动标已读、会话已读、自己一侧的软删除（两侧都删则物理删除）、未读计数，
 * 以及私信接口的登录 / 封禁 / 开关语义（与迁移前 core 一致）。
 */
class MessageCenterTest {

    private final SocialProfileFakes.FakeUsers users = new SocialProfileFakes.FakeUsers()
            .add(1, "甲", "")
            .add(2, "乙", "");
    private final SocialProfileFakes.FakeMessages store = new SocialProfileFakes.FakeMessages();
    private final MessageCenter messages = new MessageCenter(store, users);

    @Test
    void sendRequiresExistingRecipientAndRejectsSelf() {
        WikiMessage message = messages.send(1, "乙", "  你好  ");
        assertEquals(1L, message.getFromUserId().longValue());
        assertEquals(2L, message.getToUserId().longValue());
        assertEquals("你好", message.getContent(), "内容去首尾空白");
        assertFalse(Boolean.TRUE.equals(message.getRead()));
        assertFalse(Boolean.TRUE.equals(message.getFromDeleted()));
        assertFalse(Boolean.TRUE.equals(message.getToDeleted()));
        assertTrue(message.getCreateAt() != null);

        assertEquals("messages.userNotFound", assertThrows(IllegalArgumentException.class,
                () -> messages.send(1, "不存在", "x")).getMessage());
        assertEquals("messages.selfNotAllowed", assertThrows(IllegalArgumentException.class,
                () -> messages.send(1, "甲", "x")).getMessage());
    }

    @Test
    void inboxSentConversationAndUnreadCountFollowTheSoftDeleteFlags() {
        WikiMessage first = messages.send(1, "乙", "第一条");
        WikiMessage second = messages.send(2, "甲", "回信");

        assertEquals(1, messages.inbox(2).size(), "乙的收件箱");
        assertEquals(1, messages.sent(1).size(), "甲的发件箱");
        assertEquals(2, messages.conversation(1, 2).size(), "两个方向按时间升序");
        assertEquals(1, messages.unreadCount(2));
        assertEquals(1, messages.unreadCount(1));

        // 阅读（收件人）：自动标已读 + 记阅读时间
        WikiMessage viewed = messages.view(2, first.getId()).orElseThrow();
        assertTrue(Boolean.TRUE.equals(viewed.getRead()));
        assertTrue(viewed.getReadAt() != null);
        assertEquals(0, messages.unreadCount(2));

        // 发件人查看不标已读
        assertFalse(Boolean.TRUE.equals(messages.view(2, second.getId()).orElseThrow().getRead()),
                "发件人打开自己发的私信不改已读状态");

        // 无关用户看不到
        assertTrue(messages.view(3, first.getId()).isEmpty());

        // 打开会话把对方发来的未读标为已读
        messages.markConversationRead(1, 2);
        assertEquals(0, messages.unreadCount(1));
    }

    @Test
    void deleteIsOneSidedAndPhysicalWhenBothSidesAreGone() {
        WikiMessage message = messages.send(1, "乙", "内容");

        assertFalse(messages.delete(3, message.getId()), "无关用户不能删除");
        assertTrue(messages.delete(2, message.getId()), "收件人删自己一侧");
        assertEquals(0, messages.inbox(2).size());
        assertEquals(1, messages.sent(1).size(), "发件人仍能看到");
        assertTrue(messages.view(2, message.getId()).isEmpty(), "已删一侧看不到");

        assertTrue(messages.delete(1, message.getId()), "发件人删自己一侧");
        assertTrue(messages.sent(1).isEmpty());
        assertTrue(store.rows.isEmpty(), "两侧都删除后物理删除记录");
    }

    @Test
    void messageApiKeepsLoginSwitchAndRuleSemantics() {
        SocialProfileFakes.FakeMessages apiStore = new SocialProfileFakes.FakeMessages();
        MessageCenter center = new MessageCenter(apiStore, users);
        Viewer alice = SocialProfileFakes.viewer(1, "甲");

        // 功能关闭 → 403
        MessagesApiController disabled = new MessagesApiController(center, SocialProfileFakes.access(null),
                SocialProfileFakes.currentUser(alice), SocialProfileFakes.support(false));
        assertEquals(AjaxCode.FORBIDDEN, AjaxCode.fromCode(disabled.send("乙", "x").getCode()).orElseThrow());
        assertEquals(AjaxCode.FORBIDDEN, AjaxCode.fromCode(disabled.delete(1).getCode()).orElseThrow());

        // 未登录 → 401
        MessagesApiController anonymous = new MessagesApiController(center, SocialProfileFakes.access(null),
                SocialProfileFakes.currentUser(Viewer.ANONYMOUS), SocialProfileFakes.support(true));
        assertEquals(AjaxCode.UNAUTHORIZED, AjaxCode.fromCode(anonymous.send("乙", "x").getCode()).orElseThrow());

        // 被封禁 → 403 + 封禁说明
        MessagesApiController blocked = new MessagesApiController(center,
                SocialProfileFakes.access("你已被封禁，不能发送私信。"),
                SocialProfileFakes.currentUser(alice), SocialProfileFakes.support(true));
        assertEquals("你已被封禁，不能发送私信。", blocked.send("乙", "x").getMessage());

        MessagesApiController controller = new MessagesApiController(center,
                SocialProfileFakes.access(null), SocialProfileFakes.currentUser(alice),
                SocialProfileFakes.support(true));

        // 参数与规则错误 → 400 + 本地化文案
        assertEquals("请输入收件人用户名。", controller.send("  ", "x").getMessage());
        assertEquals("私信内容不能为空。", controller.send("乙", "  ").getMessage());
        assertEquals("收件人不存在。", controller.send("不存在", "x").getMessage());
        assertEquals("不能给自己发送私信。", controller.send("甲", "x").getMessage());

        // 发送成功 → 200 + id
        AjaxResult<Map<String, Object>> sent = controller.send("乙", "你好 '''乙'''");
        assertEquals(AjaxCode.SUCCESS, AjaxCode.fromCode(sent.getCode()).orElseThrow());
        assertEquals("私信已发送。", sent.getMessage());
        assertTrue(sent.getData().get("id") instanceof Long);
        assertEquals(1, center.inbox(2).size());
        assertEquals(1, center.unreadCount(2));

        // 删除：自己一侧成功、不存在的私信 404
        long id = (Long) sent.getData().get("id");
        assertEquals("私信已删除。", controller.delete(id).getMessage());
        assertTrue(center.sent(1).isEmpty(), "发件箱清空（自己一侧已删除）");
        assertEquals(AjaxCode.NOT_FOUND, AjaxCode.fromCode(controller.delete(9999).getCode()).orElseThrow());
    }
}
