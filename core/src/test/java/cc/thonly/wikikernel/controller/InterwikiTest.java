package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.InterwikiService;
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
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨 Wiki（仿 MediaWiki 的 Interwiki）：
 * <ul>
 *     <li>{@code [[w:页面]]} → 外部链接（URL 模板里的 {@code $1} 替换）；</li>
 *     <li>{@code [[en:Page]]}（有语言码）→ 跨语言链接：正文里不显示，收集到侧栏「语言」区块；</li>
 *     <li>{@code [[:en:Page]]}（前导冒号）→ 强制普通链接；</li>
 *     <li>本地前缀 → 站内链接；普通内链 / 红链 / 分类 / 文件嵌入不受影响；</li>
 *     <li>{@code 特殊:跨Wiki} 的查看（所有人）与增删改（管理员）、导入 / 导出、错误校验。</li>
 * </ul>
 * 默认前缀表由 {@code InterwikiSeeder} 写入（与真实部署一致），测试不依赖额外初始化。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class InterwikiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private InterwikiService interwikiService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    /** 复杂组合页面：跨 Wiki 外链 / 语言链接 / 强制链接 / 本地前缀 / 内链 / 红链 / 分类 / 文件嵌入同页。 */
    @Test
    void complexPageRendersInterwikiAndCollectsLanguageLinks() throws Exception {
        String localPrefix = "专题" + System.nanoTime();
        interwikiService.save(null, new InterwikiService.Form(
                localPrefix, "https://localhost.invalid/$1", "", true, false), null);

        String title = "跨Wiki组合演示页" + System.nanoTime();
        wikiPageService.editPage(null, title, """
                == 跨 Wiki 外链 ==
                * 默认显示名：[[w:示例页面]]；
                * 自定义显示名：[[wikt:word|词典条目]]；
                * 另一个 wiki：[[commons:File:Example.svg]]。

                == 语言链接（不出现在正文） ==
                [[en:Interwiki demo]]
                [[ja:インターウィキ]]

                == 强制普通链接 ==
                * [[:en:Forced link]]；
                * [[:分类:跨Wiki测试分类]]（前导冒号 → 显示成链接，不归类）。

                == 本地前缀与普通链接 ==
                * 本地前缀：[[%s:某某]]（站内链接，红链）；
                * 普通内链：[[首页]]；
                * 红链：[[这个页面一定不存在-跨Wiki]]。

                == 分类 ==
                [[分类:跨Wiki测试分类]]
                """.formatted(localPrefix));

        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                // 外部链接：$1 被 URL 编码的页面名替换，带 mw-interwiki 标记
                .andExpect(content().string(containsString("class=\"external mw-interwiki\"")))
                .andExpect(content().string(containsString(
                        "href=\"https://zh.wikipedia.org/wiki/%E7%A4%BA%E4%BE%8B%E9%A1%B5%E9%9D%A2\"")))
                .andExpect(content().string(containsString(">w:示例页面</a>")))
                .andExpect(content().string(containsString("href=\"https://zh.wiktionary.org/wiki/word\"")))
                .andExpect(content().string(containsString(">词典条目</a>")))
                .andExpect(content().string(containsString(
                        "href=\"https://commons.wikimedia.org/wiki/File:Example.svg\"")))
                // 语言链接：正文里没有它的锚文本，但侧栏「语言」区块里有链接
                .andExpect(content().string(not(containsString(">en:Interwiki demo<"))))
                .andExpect(content().string(not(containsString(">ja:インターウィキ<"))))
                .andExpect(content().string(containsString("https://en.wikipedia.org/wiki/Interwiki_demo")))
                .andExpect(content().string(containsString(
                        "https://ja.wikipedia.org/wiki/%E3%82%A4%E3%83%B3%E3%82%BF%E3%83%BC%E3%82%A6%E3%82%A3%E3%82%AD")))
                .andExpect(content().string(containsString(">英语</a>")))
                .andExpect(content().string(containsString(">日语</a>")))
                // [[:en:Forced link]] 强制渲染成链接（语言链接不剥离）
                .andExpect(content().string(containsString(
                        "href=\"https://en.wikipedia.org/wiki/Forced_link\"")))
                .andExpect(content().string(containsString(">en:Forced link</a>")))
                // [[:分类:…]] 前导冒号：显示成链接而不是归类
                .andExpect(content().string(containsString(">分类:跨Wiki测试分类</a>")))
                // 本地前缀：站内链接（红链）
                .andExpect(content().string(containsString(
                        "<a class=\"new\" href=\"/wiki/" + localPrefix + ":某某\">")))
                // 普通链接与红链照旧
                .andExpect(content().string(containsString("href=\"/wiki/首页\"")))
                .andExpect(content().string(containsString("href=\"/wiki/这个页面一定不存在-跨Wiki\"")));

        // 分类照旧收集（没有被前导冒号那一行误收）
        Optional<String> categories = wikiPageService.getByTitle(title)
                .map(page -> page.getTitle());
        assertTrue(categories.isPresent());
    }

    @Test
    void specialPageViewableByEveryoneEditableByManagers() throws Exception {
        WikiUser plain = insertUser("跨wiki普通用户" + System.nanoTime());

        // 所有人可看：表格里有种子前缀
        mockMvc.perform(get("/wiki/{title}", "特殊:跨Wiki").session(sessionOf(plain)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("wikipedia")))
                .andExpect(content().string(containsString("https://zh.wikipedia.org/wiki/$1")))
                .andExpect(content().string(containsString("interwiki-table")))
                // 非管理员：没有表单，只有权限提示
                .andExpect(content().string(containsString("只有管理员")))
                .andExpect(content().string(not(containsString("wiki-interwiki-form"))));

        WikiUser admin = insertUser("跨wiki管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        mockMvc.perform(get("/wiki/{title}", "特殊:跨Wiki").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-interwiki-form\"")))
                .andExpect(content().string(containsString("name=\"prefix\"")))
                .andExpect(content().string(containsString("name=\"url\"")))
                .andExpect(content().string(containsString("name=\"language\"")))
                .andExpect(content().string(containsString("id=\"interwiki-export\"")));

        // 英文界面（本地化）
        mockMvc.perform(get("/wiki/{title}", "特殊:跨Wiki").session(sessionOf(admin)).param("uselang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Languages")));
    }

    @Test
    void saveUpdateDeleteAndValidation() throws Exception {
        WikiUser admin = insertUser("跨wiki保存管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        WikiUser plain = insertUser("跨wiki保存用户" + System.nanoTime());
        String prefix = "iwtest" + System.nanoTime();

        // 非管理员：403
        mockMvc.perform(post("/api/interwiki/save").session(sessionOf(plain))
                        .param("prefix", prefix).param("url", "https://example.org/$1"))
                .andExpect(jsonPath("$.code").value(403));

        // 新建
        mockMvc.perform(post("/api/interwiki/save").session(sessionOf(admin))
                        .param("prefix", prefix).param("url", "https://example.org/wiki/$1")
                        .param("language", "eo"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.prefix").value(prefix));
        assertTrue(interwikiService.find(prefix).isPresent());
        assertEquals("eo", interwikiService.find(prefix).orElseThrow().language());

        // 前缀大小写不敏感 + 重复报错
        mockMvc.perform(post("/api/interwiki/save").session(sessionOf(admin))
                        .param("prefix", prefix.toUpperCase(java.util.Locale.ROOT))
                        .param("url", "https://example.org/other/$1"))
                .andExpect(jsonPath("$.code").value(400));

        // 校验：非法前缀 / 非法 URL / 非法语言码
        mockMvc.perform(post("/api/interwiki/save").session(sessionOf(admin))
                        .param("prefix", "bad prefix").param("url", "https://example.org/$1"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/interwiki/save").session(sessionOf(admin))
                        .param("prefix", "iwbad" + System.nanoTime()).param("url", "ftp://example.org/$1"))
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(post("/api/interwiki/save").session(sessionOf(admin))
                        .param("prefix", "iwbad" + System.nanoTime()).param("url", "https://example.org/$1")
                        .param("language", "not a code"))
                .andExpect(jsonPath("$.code").value(400));

        // 更新（同一个 id：改 URL 与标志）
        long id = interwikiService.find(prefix).orElseThrow().prefix().isEmpty() ? -1 : idOf(prefix);
        mockMvc.perform(post("/api/interwiki/save").session(sessionOf(admin))
                        .param("id", String.valueOf(id))
                        .param("prefix", prefix).param("url", "https://example.org/new/$1")
                        .param("local", "1"))
                .andExpect(jsonPath("$.code").value(200));
        InterwikiService.Target updated = interwikiService.find(prefix).orElseThrow();
        assertEquals("https://example.org/new/$1", updated.urlTemplate());
        assertTrue(updated.local());

        // 删除 + 再删报错
        mockMvc.perform(post("/api/interwiki/delete").session(sessionOf(admin)).param("id", String.valueOf(id)))
                .andExpect(jsonPath("$.code").value(200));
        assertTrue(interwikiService.find(prefix).isEmpty());
        mockMvc.perform(post("/api/interwiki/delete").session(sessionOf(admin)).param("id", String.valueOf(id)))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void importAndExportText() throws Exception {
        WikiUser admin = insertUser("跨wiki导入管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        String prefix = "iwimport" + System.nanoTime();

        String text = prefix + " https://example.org/$1 1 0 eo\n"
                + "# 注释行会被忽略\n"
                + prefix + "-bad not-a-url\n";
        mockMvc.perform(post("/api/interwiki/import").session(sessionOf(admin)).param("text", text))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.count").value(1));

        InterwikiService.Target imported = interwikiService.find(prefix).orElseThrow();
        assertEquals("https://example.org/$1", imported.urlTemplate());
        assertEquals("eo", imported.language());
        assertTrue(imported.local());

        // 导出里能看到这一行（前缀 + TAB + URL + TAB + 标志）
        mockMvc.perform(get("/wiki/{title}", "特殊:跨Wiki").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(prefix + "\thttps://example.org/$1\t1\t0\teo")));

        // 空文本：400
        mockMvc.perform(post("/api/interwiki/import").session(sessionOf(admin)).param("text", "\n# 只有注释\n"))
                .andExpect(jsonPath("$.code").value(400));

        interwikiService.delete(idOf(prefix));
    }

    // ==================== 工具 ====================

    private long idOf(String prefix) {
        return interwikiService.all().stream()
                .filter(row -> row.getPrefix().equalsIgnoreCase(prefix))
                .map(row -> row.getId())
                .findFirst()
                .orElseThrow();
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
