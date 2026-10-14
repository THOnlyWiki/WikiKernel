package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「机器人」用户组（仿 MediaWiki 的 bot 组）：
 * <ul>
 *     <li>组是内置权限类型，可在 特殊:用户权限 授予；</li>
 *     <li>组内用户编辑页有「标记此编辑为机器人编辑」复选框，勾选后版本 {@code bot=true}；</li>
 *     <li>普通用户即使提交 botEdit 也不会生效；</li>
 *     <li>最近更改默认隐藏机器人编辑（标记为「机」），{@code ?hidebots=0} 显示。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class BotGroupTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private BlockService blockService;

    @Test
    void botGroupCanMarkEditsAsBot() throws Exception {
        assertTrue(permissionService.allTypes().stream().anyMatch(type -> type.getName().equals("机器人")),
                "应内置「机器人」用户组");

        WikiUser bot = insertUser("机器人用户" + System.nanoTime());
        permissionService.replaceFor(bot.getId(), List.of(PermissionService.GROUP_BOT));
        String title = "机器人测试页" + System.nanoTime();

        // 编辑页出现「标记此编辑为机器人编辑」
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(bot)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"botEdit\"")));

        // 保存时勾选 → 版本 bot=true
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(bot))
                        .param("content", "机器人写的").param("botEdit", "1"))
                .andExpect(status().is3xxRedirection());
        WikiPage page = wikiPageService.getByTitle(title).orElseThrow();
        WikiRevision revision = wikiPageService.getLatestRevision(page.getId()).orElseThrow();
        assertTrue(Boolean.TRUE.equals(revision.getBot()), "机器人编辑应打上 bot 标记");

        // 最近更改默认隐藏；?hidebots=0 显示（带「机」标记）
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(title))));
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改").param("hidebots", "0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(title)))
                .andExpect(content().string(containsString("rc-bot")));
    }

    @Test
    void recentChangesFilterChipsAndEmptyState() throws Exception {
        // 默认隐藏机器人 → 显示「人工（非机器人）」过滤器，且没有空状态文案
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("人工（非机器人）")))
                .andExpect(content().string(containsString("rc-chip")))
                .andExpect(content().string(not(containsString("没有激活的过滤器"))));

        // 去掉所有过滤器（?hidebots=0）→ 空状态提示（仿 MediaWiki RCFilters）
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改").param("hidebots", "0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("没有激活的过滤器。所有贡献均已显示。")))
                .andExpect(content().string(not(containsString("人工（非机器人）"))))
                .andExpect(content().string(not(containsString("rc-chip-trash"))));

        // 加了命名空间过滤器 → 显示 chip（不是空状态），并带移除链接
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改")
                        .param("hidebots", "0").param("ns", "模板:"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("没有激活的过滤器"))))
                .andExpect(content().string(containsString("rc-chip")))
                .andExpect(content().string(containsString("rc-chip-trash")));
    }

    @Test
    void botLogEntriesAreHiddenByDefaultAndMarkedWhenShown() throws Exception {
        WikiUser bot = insertUser("机器人日志用户" + System.nanoTime());
        permissionService.replaceFor(bot.getId(), List.of(PermissionService.GROUP_BOT));
        // 用封禁日志验证（不涉及页面版本；移动页会把页面自身的版本也带进最近更改）
        String target = "机器人封禁目标" + System.nanoTime();
        blockService.block(bot, target, null, "测试封禁", false, false, false);

        // 默认：机器人用户的日志操作也隐藏
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(target))));
        // ?hidebots=0：显示并标「机」
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改").param("hidebots", "0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(target)))
                .andExpect(content().string(containsString("rc-bot")));
    }

    @Test
    void nonBotCannotMarkEdits() throws Exception {
        WikiUser normal = insertUser("非机器人用户" + System.nanoTime());
        String title = "非机器人测试页" + System.nanoTime();
        // 普通用户编辑页没有该复选框
        mockMvc.perform(get("/wiki/{title}", title).param("action", "edit").session(sessionOf(normal)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("name=\"botEdit\""))));

        // 即使强行提交 botEdit 也不生效
        mockMvc.perform(post("/wiki/{title}", title).with(csrf()).session(sessionOf(normal))
                        .param("content", "普通人写的").param("botEdit", "1"))
                .andExpect(status().is3xxRedirection());
        WikiPage page = wikiPageService.getByTitle(title).orElseThrow();
        WikiRevision revision = wikiPageService.getLatestRevision(page.getId()).orElseThrow();
        assertFalse(Boolean.TRUE.equals(revision.getBot()), "非机器人用户不能打 bot 标记");
    }

    // ==================== 工具 ====================

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
