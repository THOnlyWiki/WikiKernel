package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiBlock;
import cc.thonly.wikikernel.common.data.WikiLog;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 封禁用户 / IP（仿 MediaWiki Special:Block）：
 * 永久 / 定时、禁止发送邮件、禁止创建账号、仅封禁匿名；
 * 被封禁者编辑页显示「您已被封禁」、保存 / 上传 / 移动被 403；解封后恢复；
 * 封禁 / 解封写日志；权限动作 {@code block}（默认管理员）。
 * <p>
 * 独立上下文：开启了 trust-proxy-headers，便于用 CF 头模拟客户端 IP。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.http.trust-proxy-headers=true"
})
@AutoConfigureMockMvc
class BlockTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private BlockService blockService;

    @Autowired
    private LogService logService;

    @Test
    void blocksUserThenEditIsForbiddenAndUnblockRestores() throws Exception {
        WikiUser admin = admin();
        WikiUser target = insertUser("封禁目标" + System.nanoTime());
        String page = "封禁测试页" + System.nanoTime();
        wikiPageService.editPage(null, page, "正文");

        // 封禁：永久 + 禁邮件
        mockMvc.perform(post("/api/blocks").session(sessionOf(admin))
                        .param("target", target.getUsername())
                        .param("expiry", "infinite")
                        .param("reason", "破坏性编辑")
                        .param("preventEmail", "true"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.target").value(target.getUsername()))
                // 封禁后回到封禁页（不再跳封禁列表），带成功提示参数
                .andExpect(jsonPath("$.data.redirectUrl").value(containsString("blocked=1")))
                .andExpect(jsonPath("$.data.redirectUrl").value(containsString("target=")));

        // 编辑页显示封禁页面（原因 / 永久 / 封禁者）
        mockMvc.perform(get("/wiki/{title}", page).param("action", "edit").session(sessionOf(target)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("您已被封禁")))
                .andExpect(content().string(containsString("破坏性编辑")))
                .andExpect(content().string(containsString("永久")))
                .andExpect(content().string(containsString(admin.getUsername())))
                .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));

        // 保存编辑 403
        mockMvc.perform(post("/wiki/{title}", page).session(sessionOf(target)).with(csrf())
                        .param("content", "越权修改"))
                .andExpect(status().isForbidden());

        // 发送确认邮件被拒
        mockMvc.perform(post("/api/user/email/confirm").session(sessionOf(target)))
                .andExpect(jsonPath("$.code").value(403));

        // 封禁列表：目标 / 原因 / 选项 / 解封按钮
        mockMvc.perform(get("/wiki/{title}", "特殊:封禁列表").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(target.getUsername())))
                .andExpect(content().string(containsString("破坏性编辑")))
                .andExpect(content().string(containsString("禁止发送电子邮件")))
                .andExpect(content().string(containsString("block-unblock")));

        // 封禁页：目标已封禁时显示「解封」按钮与成功提示
        mockMvc.perform(get("/wiki/{title}", "特殊:封禁").param("target", target.getUsername())
                        .param("blocked", "1").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("该目标当前已被封禁")))
                .andExpect(content().string(containsString("block-unblock")))
                .andExpect(content().string(containsString("已封禁")));

        // 封禁写日志
        List<WikiLog> logs = logService.search("block", null, null, null, null, true, 20);
        assertTrue(logs.stream().anyMatch(log -> target.getUsername().equals(log.getTarget())), "应写封禁日志");

        // 最近更改里混排「封禁日志」
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("封禁日志")))
                .andExpect(content().string(containsString(target.getUsername())));

        // 解封 → 恢复编辑；封禁页显示「已解封」提示
        long blockId = blockService.findActiveByTarget(target.getUsername()).orElseThrow().getId();
        mockMvc.perform(post("/api/blocks/unblock").session(sessionOf(admin)).param("id", String.valueOf(blockId)))
                .andExpect(jsonPath("$.code").value(200));
        // 最近更改里混排「解封日志」
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("解封日志")));
        mockMvc.perform(get("/wiki/{title}", "特殊:封禁").param("target", target.getUsername())
                        .param("unblocked", "1").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已解封")));
        mockMvc.perform(get("/wiki/{title}", page).param("action", "edit").session(sessionOf(target)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")))
                .andExpect(content().string(not(containsString("您已被封禁"))));
    }

    @Test
    void timedBlockWorksAndExpiredBlockDoesNot() {
        WikiUser target = insertUser("定时封禁目标" + System.nanoTime());

        // 已过期：不生效
        blockService.block(null, target.getUsername(), LocalDateTime.now().minusMinutes(1), "过期封禁", false, false, false);
        assertFalse(blockService.currentBlock(target).isPresent(), "已过期的封禁不应生效");
        assertTrue(blockService.activeBlocks().stream()
                .noneMatch(block -> target.getUsername().equals(block.getTarget())));

        // 1 小时：生效，到期时间不为空
        WikiBlock block = blockService.block(null, target.getUsername(), LocalDateTime.now().plusHours(1),
                "定时封禁", false, false, false);
        assertTrue(block.getExpiresAt() != null);
        assertTrue(blockService.currentBlock(target).isPresent());
        // 清理
        blockService.unblock(null, block.getId());
    }

    @Test
    void ipBlockCidrBlocksUsersAndAnonymousRegistration() throws Exception {
        WikiUser admin = admin();
        WikiUser user = insertUser("IP封禁用户" + System.nanoTime());
        String page = "IP封禁测试页" + System.nanoTime();
        wikiPageService.editPage(null, page, "正文");
        String blockedIp = "198.51.100.9";

        // CIDR 封禁 + 禁止创建账号
        mockMvc.perform(post("/api/blocks").session(sessionOf(admin))
                        .param("target", "198.51.100.0/24")
                        .param("expiry", "1d")
                        .param("reason", "IP 段破坏")
                        .param("preventCreate", "true"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.targetType").value("ip"));
        long blockId = blockService.findActiveByTarget("198.51.100.0/24").orElseThrow().getId();

        // 从被封 IP 登录用户编辑：显示封禁页 + 保存 403
        mockMvc.perform(get("/wiki/{title}", page).param("action", "edit")
                        .session(sessionOf(user)).header("CF-Connecting-IP", blockedIp))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("您已被封禁")))
                .andExpect(content().string(containsString("IP 段破坏")));
        mockMvc.perform(post("/wiki/{title}", page).session(sessionOf(user)).with(csrf())
                        .header("CF-Connecting-IP", blockedIp).param("content", "x"))
                .andExpect(status().isForbidden());

        // 其他 IP 不受影响
        mockMvc.perform(get("/wiki/{title}", page).param("action", "edit")
                        .session(sessionOf(user)).header("CF-Connecting-IP", "192.0.2.5"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));

        // 同 IP 段注册被拒
        mockMvc.perform(post("/api/user/register").header("CF-Connecting-IP", blockedIp)
                        .param("username", "注册拦截" + System.nanoTime())
                        .param("password", "secret123"))
                .andExpect(jsonPath("$.code").value(403));

        blockService.unblock(admin, blockId);
    }

    @Test
    void anonOnlyIpBlockDoesNotAffectLoggedInUsers() throws Exception {
        WikiUser admin = admin();
        WikiUser user = insertUser("仅匿名用户" + System.nanoTime());
        String page = "仅匿名封禁页" + System.nanoTime();
        wikiPageService.editPage(null, page, "正文");
        String ip = "203.0.113.77";

        mockMvc.perform(post("/api/blocks").session(sessionOf(admin))
                        .param("target", "203.0.113.0/24")
                        .param("expiry", "1h")
                        .param("anonOnly", "true"))
                .andExpect(jsonPath("$.code").value(200));
        long blockId = blockService.findActiveByTarget("203.0.113.0/24").orElseThrow().getId();

        // 匿名：被封
        mockMvc.perform(get("/wiki/{title}", page).param("action", "edit").header("CF-Connecting-IP", ip))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("您已被封禁")));
        // 登录用户：不受影响
        mockMvc.perform(get("/wiki/{title}", page).param("action", "edit")
                        .session(sessionOf(user)).header("CF-Connecting-IP", ip))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")));

        blockService.unblock(admin, blockId);
    }

    @Test
    void blockPermissionIsConfigurable() throws Exception {
        // 普通用户 / 匿名：不能封禁
        WikiUser user = insertUser("无权封禁用户" + System.nanoTime());
        mockMvc.perform(post("/api/blocks").param("target", "某人").param("expiry", "1d"))
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/blocks").session(sessionOf(user))
                        .param("target", "某人").param("expiry", "1d"))
                .andExpect(jsonPath("$.code").value(403));

        // 特殊:封禁 页面对普通用户显示无权限提示，且没有表单
        mockMvc.perform(get("/wiki/{title}", "特殊:封禁").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("只有管理员可以封禁")))
                .andExpect(content().string(not(containsString("id=\"wiki-block-form\""))));

        // 管理员：表单可见（带期限下拉与选项）
        WikiUser admin = admin();
        mockMvc.perform(get("/wiki/{title}", "特殊:封禁").param("target", "示例目标").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-block-form\"")))
                .andExpect(content().string(containsString("永久")))
                .andExpect(content().string(containsString("禁止发送电子邮件")))
                .andExpect(content().string(containsString("/js/block.js")));
    }

    // ==================== 工具 ====================

    private WikiUser admin() {
        WikiUser admin = insertUser("封禁管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        return admin;
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
