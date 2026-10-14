package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 随机页面 / 最新页面：
 * <ul>
 *     <li>{@code 特殊:随机页面}（别名 特殊:Random）：302 到随机可见页面，永远不落到无权页面；支持命名空间；</li>
 *     <li>{@code 特殊:最新页面}（别名 特殊:NewPages）：列出新页面（时间 / 页面 + 差异 / 历史 / 创建者 / 大小 / 摘要），
 *     无权页面不出现，支持命名空间与条数筛选。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class RandomAndNewPagesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private AccessControlService accessControlService;

    @Test
    void randomRedirectsToReadablePageAndSupportsNamespace() throws Exception {
        String stamp = String.valueOf(System.nanoTime());
        String first = "随机测试甲" + stamp;
        String second = "随机测试乙" + stamp;
        wikiPageService.editPage(null, first, "a");
        wikiPageService.editPage(null, second, "b");

        String restricted = "随机受限页" + stamp;
        wikiPageService.editPage(null, restricted, "secret");
        accessControlService.saveRule(restricted, AccessAction.READ, PermissionService.GROUP_ADMIN);
        try {
            // 匿名随机多次：永远是 302，且不会落到无权页面
            String restrictedPath = URLEncoder.encode(restricted, StandardCharsets.UTF_8);
            for (int i = 0; i < 10; i++) {
                MvcResult result = mockMvc.perform(get("/wiki/{title}", "特殊:随机页面"))
                        .andExpect(status().is3xxRedirection())
                        .andReturn();
                String location = result.getResponse().getRedirectedUrl();
                assertNotNull(location, "随机页面应 302 到某个页面");
                assertTrue(location.startsWith("/wiki/"), "应跳到站内页面：" + location);
                assertFalse(location.contains(restrictedPath), "无权页面不应被随机到：" + location);
            }

            // 英文别名同样可用
            mockMvc.perform(get("/wiki/{title}", "特殊:Random"))
                    .andExpect(status().is3xxRedirection());

            // 命名空间筛选：模板命名空间
            String template = "随机模板" + stamp;
            wikiPageService.editPage(null, "模板:" + template, "x");
            MvcResult templateResult = mockMvc.perform(get("/wiki/{title}", "特殊:随机页面")
                            .param("namespace", "template"))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();
            assertTrue(templateResult.getResponse().getRedirectedUrl()
                            .startsWith("/wiki/" + URLEncoder.encode("模板:", StandardCharsets.UTF_8)),
                    "应随机到模板命名空间：" + templateResult.getResponse().getRedirectedUrl());
        } finally {
            accessControlService.findRule(AccessAction.READ, restricted)
                    .ifPresent(rule -> accessControlService.deleteRule(rule.getId()));
        }
    }

    @Test
    void newPagesListsRecentlyCreatedPages() throws Exception {
        WikiUser user = insertUser("新页面用户" + System.nanoTime());
        String title = "新页面测试" + System.nanoTime();
        wikiPageService.editPage(user, title, "创建内容", "创建摘要");

        String restricted = "新页面受限" + System.nanoTime();
        wikiPageService.editPage(user, restricted, "x", "受限摘要");
        accessControlService.saveRule(restricted, AccessAction.READ, PermissionService.GROUP_ADMIN);
        try {
            mockMvc.perform(get("/wiki/{title}", "特殊:最新页面"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(title)))
                    .andExpect(content().string(containsString(user.getUsername())))
                    .andExpect(content().string(containsString("创建摘要")))
                    .andExpect(content().string(containsString("diff=prev")))
                    .andExpect(content().string(containsString("action=history")))
                    .andExpect(content().string(not(containsString(restricted))));

            // 英文别名与条数筛选
            mockMvc.perform(get("/wiki/{title}", "特殊:NewPages").param("limit", "100"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("name=\"limit\"")))
                    .andExpect(content().string(containsString(title)));
        } finally {
            accessControlService.findRule(AccessAction.READ, restricted)
                    .ifPresent(rule -> accessControlService.deleteRule(rule.getId()));
        }
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }
}
