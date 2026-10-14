package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 替换文本（仿 MediaWiki Special:ReplaceText）：
 * 管理员权限、查找（literal / 正则、标题前缀、匹配数 / 替换后预览）、应用替换（$1 / $0 反向引用）、参数校验。
 * <p>
 * 用例共享上下文数据库，查找词都带唯一后缀，避免其它用例的页面误入结果。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class ReplaceTextTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void replaceTextRequiresManagePermission() throws Exception {
        WikiUser user = insertUser("替换文本普通用户" + System.nanoTime());

        // 匿名 / 普通用户：只有提示，没有查找表单
        mockMvc.perform(get("/wiki/{title}", "特殊:替换文本"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("需要管理员权限。")))
                .andExpect(content().string(not(containsString("id=\"replace-text-search\""))))
                .andExpect(content().string(not(containsString("id=\"wiki-replace-text-form\""))));
        mockMvc.perform(get("/wiki/{title}", "特殊:替换文本").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("需要管理员权限。")))
                .andExpect(content().string(not(containsString("id=\"replace-text-search\""))));

        // POST：匿名 401，普通用户 403
        mockMvc.perform(post("/api/replace-text/apply").param("search", "苹果").param("titles", "某页面"))
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/replace-text/apply").session(sessionOf(user))
                        .param("search", "苹果").param("titles", "某页面"))
                .andExpect(jsonPath("$.code").value(403));

        // 管理员：表单可见；英文别名同样可访问
        WikiUser admin = admin();
        mockMvc.perform(get("/wiki/{title}", "特殊:替换文本").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"replace-text-search\"")))
                .andExpect(content().string(containsString("id=\"replace-text-prefix\"")))
                .andExpect(content().string(containsString("/js/replace-text.js")));
        mockMvc.perform(get("/wiki/{title}", "Special:ReplaceText").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"replace-text-search\"")));
    }

    @Test
    void findsAndReplacesLiteralTextInSelectedPages() throws Exception {
        WikiUser admin = admin();
        String token = String.valueOf(System.nanoTime());
        String apple = "苹果" + token;
        String banana = "香蕉" + token;
        String title1 = "替换文本测试甲" + token;
        String title2 = "替换文本测试乙" + token;
        String title3 = "替换文本测试丙" + token;
        wikiPageService.editPage(admin, title1, "这里有一个" + apple + "，很好吃。");
        wikiPageService.editPage(admin, title2, apple + "，" + apple + "，还是" + apple + "。");
        wikiPageService.editPage(admin, title3, "这里没有那种水果。");

        // 查找：两个命中页的标题、匹配数（第二页 3 处）与替换后的预览都在；未命中的不出现
        mockMvc.perform(get("/wiki/{title}", "特殊:替换文本").session(sessionOf(admin))
                        .param("search", apple).param("replace", "香蕉"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("匹配结果（2 个页面）")))
                .andExpect(content().string(containsString("单次最多扫描 1,000 个页面")))
                .andExpect(content().string(containsString(title1)))
                .andExpect(content().string(containsString(title2)))
                .andExpect(content().string(not(containsString(title3))))
                .andExpect(content().string(containsString(">3<")))
                .andExpect(content().string(containsString("这里有一个香蕉，很好吃。")));

        // 标题前缀过滤：只保留甲
        mockMvc.perform(get("/wiki/{title}", "特殊:替换文本").session(sessionOf(admin))
                        .param("search", apple).param("replace", "香蕉").param("prefix", title1))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(title1)))
                .andExpect(content().string(not(containsString(title2))));

        // 应用替换：两个页面都改成香蕉
        mockMvc.perform(post("/api/replace-text/apply").session(sessionOf(admin))
                        .param("titles", title1).param("titles", title2)
                        .param("search", apple).param("replace", banana))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("已替换 2 个页面（跳过 0 个）。"))
                .andExpect(jsonPath("$.data.replaced").value(2))
                .andExpect(jsonPath("$.data.skipped").value(0));
        assertEquals("这里有一个" + banana + "，很好吃。", contentOf(title1));
        assertEquals(banana + "，" + banana + "，还是" + banana + "。", contentOf(title2));
        assertEquals("这里没有那种水果。", contentOf(title3));

        // 再次替换：正文已无匹配 → 跳过
        mockMvc.perform(post("/api/replace-text/apply").session(sessionOf(admin))
                        .param("titles", title1).param("search", apple).param("replace", banana))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replaced").value(0))
                .andExpect(jsonPath("$.data.skipped").value(1));
    }

    @Test
    void regexReplacementSupportsBackReferences() throws Exception {
        WikiUser admin = admin();
        String token = String.valueOf(System.nanoTime());
        String title = "替换文本正则测试" + token;
        wikiPageService.editPage(admin, title, "梨子" + token + "真好吃");

        // GET 正则查找：预览里是 find 替换后的文本
        mockMvc.perform(get("/wiki/{title}", "特殊:替换文本").session(sessionOf(admin))
                        .param("search", "梨.").param("replace", "香蕉").param("regex", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(title)))
                .andExpect(content().string(containsString("香蕉" + token)));

        // $1 / $2 反向引用：梨 + 子 → 子 + 梨
        mockMvc.perform(post("/api/replace-text/apply").session(sessionOf(admin))
                        .param("titles", title).param("search", "(梨)(子)")
                        .param("replace", "$2$1").param("regex", "1"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replaced").value(1))
                .andExpect(jsonPath("$.data.skipped").value(0));
        assertEquals("子梨" + token + "真好吃", contentOf(title));

        // $0 = 整个匹配
        mockMvc.perform(post("/api/replace-text/apply").session(sessionOf(admin))
                        .param("titles", title).param("search", "子梨")
                        .param("replace", "<$0>").param("regex", "1"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.replaced").value(1));
        assertEquals("<子梨>" + token + "真好吃", contentOf(title));

        // 非法正则：页面提示，不再渲染结果
        mockMvc.perform(get("/wiki/{title}", "特殊:替换文本").session(sessionOf(admin))
                        .param("search", "(梨").param("regex", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("正则表达式无效")))
                .andExpect(content().string(not(containsString("id=\"wiki-replace-text-form\""))));
    }

    @Test
    void applyValidatesParameters() throws Exception {
        WikiUser admin = admin();
        String title = "替换文本校验" + System.nanoTime();
        wikiPageService.editPage(admin, title, "正文");

        // search 空 → 400
        mockMvc.perform(post("/api/replace-text/apply").session(sessionOf(admin))
                        .param("search", " ").param("titles", title))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请输入查找内容。"));

        // titles 空 → 400
        mockMvc.perform(post("/api/replace-text/apply").session(sessionOf(admin))
                        .param("search", "正文"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请先选择要替换的页面。"));

        // 非法正则 → 400
        mockMvc.perform(post("/api/replace-text/apply").session(sessionOf(admin))
                        .param("search", "(正文").param("regex", "1").param("titles", title))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("正则表达式无效")));

        // 所有失败都不改动正文
        assertEquals("正文", contentOf(title));
    }

    // ==================== 工具 ====================

    private WikiUser admin() {
        WikiUser admin = insertUser("替换文本管理员" + System.nanoTime());
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

    private String contentOf(String title) {
        WikiPage page = wikiPageService.getByTitle(title).orElseThrow();
        return wikiPageService.getLatestContent(page.getId()).orElseThrow();
    }
}
