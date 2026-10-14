package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.WikiUrls;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证页面路由：阅读、旧版本、编辑、历史、特殊页与 CSS 注入。 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class WikiPageControllerTest {

    private static final LocalDateTime TEST_TIME = LocalDateTime.of(2026, 10, 4, 12, 0, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiRevisionMapper wikiRevisionMapper;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    // ==================== 阅读 ====================

    @Test
    void rendersVisiblePage() throws Exception {
        insertPage("首页", false);

        mockMvc.perform(get("/wiki/{title}", "首页"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("WikiKernel")))
                .andExpect(content().string(containsString("首页")))
                .andExpect(content().string(containsString("这是正文")))
                .andExpect(content().string(containsString("隐私政策")));
    }

    @Test
    void hiddenPageReturns404AndMissingPageIs404WithCreateLink() throws Exception {
        insertPage("隐藏页", true);

        // 被隐藏的页面仍然 404（不暴露存在性）
        mockMvc.perform(get("/wiki/{title}", "隐藏页"))
                .andExpect(status().isNotFound());

        // 不存在的页面：404（自定义 404 页提供「创建本页面」入口），不再自动跳编辑器
        MockHttpSession session = loggedInSession();
        mockMvc.perform(get("/wiki/{title}", "不存在的页面").session(session))
                .andExpect(status().isNotFound());
        // 显式进入编辑页仍然可以创建，并提示“本页面还不存在”
        mockMvc.perform(get("/wiki/{title}", "不存在的页面").param("action", "edit").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("本页面还不存在")));
        // 匿名（无创建权限）：查看源代码页也提示“本页面还不存在”
        mockMvc.perform(get("/wiki/{title}", "不存在的页面").param("action", "edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("本页面还不存在")));
    }

    @Test
    void rendersWikitextContent() throws Exception {
        insertPage("格式页", "'''粗体''' 和 [[首页|链接]] 以及 <br>", false);

        mockMvc.perform(get("/wiki/{title}", "格式页"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<b>粗体</b>")))
                .andExpect(content().string(containsString("href=\"/wiki/首页\"")))
                .andExpect(content().string(containsString("<br>")));
    }

    @Test
    void rootRedirectsToLocalizedMainPage() throws Exception {
        // 默认中文 → 「首页」
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/wiki/%E9%A6%96%E9%A1%B5"));

        // 英文：Main Page 尚未创建 → 回退到「首页」，避免根路径 404
        mockMvc.perform(get("/").param("uselang", "en"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/wiki/%E9%A6%96%E9%A1%B5"));

        // 英文：已创建 Main Page → 指向本地化的首页
        insertPage("Main Page", "English main page", false);
        mockMvc.perform(get("/").param("uselang", "en"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/wiki/Main%20Page"));
    }

    @Test
    void injectsPageCssIntoHead() throws Exception {
        insertPage("Wiki.css", "body { color: red; }", false);
        insertPage("WikiKernel:Common.css", ".common { color: green; }", false);
        insertPage("样式注入页", "正文", false);
        insertPage("样式注入页/自定义.css", ".x > .y { color: blue; }", false);

        mockMvc.perform(get("/wiki/{title}", "样式注入页"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<style>body { color: red; }</style>")))
                // WikiKernel:Common.css 也在全站队列里
                .andExpect(content().string(containsString("<style>.common { color: green; }</style>")))
                // 子选择器的 > 不能被转义破坏
                .andExpect(content().string(containsString(".x > .y { color: blue; }")));
    }

    @Test
    void pageCssCannotBreakOutOfStyleTag() throws Exception {
        insertPage("安全样式页", "正文", false);
        insertPage("安全样式页/注入.css", "</style><script>alert(1)</script>", false);

        mockMvc.perform(get("/wiki/{title}", "安全样式页"))
                .andExpect(status().isOk())
                // 样式仍被注入，但 < 已转义为 \3C
                .andExpect(content().string(containsString("\\3C /style")))
                .andExpect(content().string(not(containsString("</style><script>"))))
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))));
    }

    // ==================== 编辑与历史 ====================

    @Test
    void anonymousEditShowsSourceInsteadOfLoginRedirect() throws Exception {
        insertPage("编辑需登录", "正文内容", false);

        mockMvc.perform(get("/wiki/{title}", "编辑需登录").param("action", "edit"))
                .andExpect(status().isOk())
                // 页签显示“查看源代码”，页面提示登录并展示源码
                .andExpect(content().string(containsString("查看源代码")))
                .andExpect(content().string(containsString("您尚未登录")))
                .andExpect(content().string(containsString("正文内容")))
                .andExpect(content().string(not(containsString("id=\"wiki-edit-form\""))));
    }

    @Test
    void editAndSaveCreatesNewRevision() throws Exception {
        insertPage("历史测试页", "第一版", false);
        MockHttpSession session = loggedInSession();
        String editor = ((WikiUser) session.getAttribute(UserSession.ATTRIBUTE)).getUsername();

        mockMvc.perform(get("/wiki/{title}", "历史测试页").param("action", "edit").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-edit-form\"")))
                .andExpect(content().string(containsString("第一版")));

        mockMvc.perform(post("/wiki/{title}", "历史测试页")
                        .session(session).with(csrf())
                        .param("content", "第二版内容")
                        .param("comment", "补充说明"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/wiki/%E5%8E%86%E5%8F%B2%E6%B5%8B%E8%AF%95%E9%A1%B5"));

        mockMvc.perform(get("/wiki/{title}", "历史测试页"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("第二版内容")))
                .andExpect(content().string(not(containsString("第一版"))));

        mockMvc.perform(get("/wiki/{title}", "历史测试页").param("action", "history"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("个版本")))
                .andExpect(content().string(containsString("补充说明")))
                .andExpect(content().string(containsString("当前")))
                .andExpect(content().string(containsString(editor)));
    }

    @Test
    void historyOfMissingPageIs404() throws Exception {
        // 页面还不存在时点「查看历史」：404（自定义 404 页），不再是创建页
        String title = "历史不存在页" + System.nanoTime();
        mockMvc.perform(get("/wiki/{title}", title).param("action", "history"))
                .andExpect(status().isNotFound());
    }

    @Test
    void oldRevisionCanBeViewed() throws Exception {
        insertPage("旧版测试页", "第一版内容", false);
        MockHttpSession session = loggedInSession();

        mockMvc.perform(post("/wiki/{title}", "旧版测试页")
                        .session(session).with(csrf())
                        .param("content", "第二版内容"))
                .andExpect(status().is3xxRedirection());

        WikiPage page = wikiPageMapper.selectOne(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, "旧版测试页"));
        List<WikiRevision> revisions = wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getPageId, page.getId())
                .orderByAsc(WikiRevision::getId));
        Long oldId = revisions.get(0).getId();

        mockMvc.perform(get("/wiki/{title}", "旧版测试页").param("oldid", oldId.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("第一版内容")))
                .andExpect(content().string(containsString("old-revision-warning")))
                .andExpect(content().string(containsString("查看当前版本")));
    }

    @Test
    void editOfMissingPageCreatesIt() throws Exception {
        MockHttpSession session = loggedInSession();

        mockMvc.perform(get("/wiki/{title}", "全新的测试页面").param("action", "edit").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("本页面还不存在")));

        mockMvc.perform(post("/wiki/{title}", "全新的测试页面")
                        .session(session).with(csrf())
                        .param("content", "新创建的正文"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/wiki/{title}", "全新的测试页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("新创建的正文")));
    }

    @Test
    void staleBaseRevisionShowsConflictNoticeWithoutWriting() throws Exception {
        insertPage("冲突提示测试页", "第一行\n第二行\n第三行", false);
        WikiPage page = wikiPageMapper.selectOne(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, "冲突提示测试页"));
        Long baseRevision = page.getLatestRevisionId();

        // 其他人改了第三行（页面前进一个新版本）
        appendRevision(page, "第一行\n第二行\n第三行改");

        // 我基于旧版本提交：进入冲突提示页（显示差异与对方信息），不写入修订
        MockHttpSession session = loggedInSession();
        mockMvc.perform(post("/wiki/{title}", "冲突提示测试页")
                        .session(session).with(csrf())
                        .param("content", "第一行改\n第二行\n第三行")
                        .param("baserevid", baseRevision.toString())
                        .param("comment", "我的编辑"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("编辑冲突")))
                .andExpect(content().string(containsString("差异对照")))
                // 差异表包含对方的修改（行内高亮可能拆成多段，检查高亮 class）
                .andExpect(content().string(containsString("diffchange")))
                // 编辑框保留我的文本（没有丢内容）
                .andExpect(content().string(containsString("第一行改")));
        assertEquals(2, revisionsOf("冲突提示测试页").size());
    }

    @Test
    void conflictNoticeResaveCreatesNewRevisionFromEditor() throws Exception {
        insertPage("冲突重存测试页", "第一行\n第二行", false);
        WikiPage page = wikiPageMapper.selectOne(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, "冲突重存测试页"));
        Long baseRevision = page.getLatestRevisionId();
        appendRevision(page, "第一行\n对方改的第二行");

        MockHttpSession session = loggedInSession();
        // 第一次：冲突提示（未写入）
        mockMvc.perform(post("/wiki/{title}", "冲突重存测试页")
                        .session(session).with(csrf())
                        .param("content", "第一行\n我的第二行")
                        .param("baserevid", baseRevision.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("编辑冲突")));
        assertEquals(2, revisionsOf("冲突重存测试页").size());

        // 手动合并后从提示页再次保存：基准已是最新版本，正常落盘
        WikiPage updatedPage = wikiPageMapper.selectOne(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, "冲突重存测试页"));
        mockMvc.perform(post("/wiki/{title}", "冲突重存测试页")
                        .session(session).with(csrf())
                        .param("content", "第一行\n双方合并后的第二行")
                        .param("baserevid", updatedPage.getLatestRevisionId().toString())
                        .param("comment", "手动合并"))
                .andExpect(status().is3xxRedirection());

        WikiPage result = wikiPageMapper.selectOne(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, "冲突重存测试页"));
        WikiRevision latest = wikiRevisionMapper.selectById(result.getLatestRevisionId());
        assertEquals("第一行\n双方合并后的第二行", latest.getContent());
    }

    @Test
    void sectionEditOfUnchangedSectionStillMerges() throws Exception {
        insertPage("段落合并测试页", "== 第一节 ==\n第一段\n\n== 第二节 ==\n第二段\n", false);
        WikiPage page = wikiPageMapper.selectOne(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, "段落合并测试页"));
        Long baseRevision = page.getLatestRevisionId();

        // 其他人改了第二节；我编辑第一节 → 段落未变，安全合并
        appendRevision(page, "== 第一节 ==\n第一段\n\n== 第二节 ==\n第二段改\n");
        MockHttpSession session = loggedInSession();
        mockMvc.perform(post("/wiki/{title}", "段落合并测试页")
                        .session(session).with(csrf())
                        .param("section", "1")
                        .param("content", "== 第一节 ==\n第一段改")
                        .param("baserevid", baseRevision.toString()))
                .andExpect(status().is3xxRedirection());

        WikiPage updated = wikiPageMapper.selectOne(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, "段落合并测试页"));
        WikiRevision latest = wikiRevisionMapper.selectById(updated.getLatestRevisionId());
        assertTrue(latest.getContent().contains("第一段改"), latest.getContent());
        assertTrue(latest.getContent().contains("第二段改"), latest.getContent());
    }

    @Test
    void sectionEditOfChangedSectionShowsConflictNotice() throws Exception {
        insertPage("段落冲突测试页", "== 第一节 ==\n第一段\n", false);
        WikiPage page = wikiPageMapper.selectOne(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, "段落冲突测试页"));
        Long baseRevision = page.getLatestRevisionId();

        // 其他人也改了第一节
        appendRevision(page, "== 第一节 ==\n对方改的第一段\n");
        MockHttpSession session = loggedInSession();
        mockMvc.perform(post("/wiki/{title}", "段落冲突测试页")
                        .session(session).with(csrf())
                        .param("section", "1")
                        .param("content", "== 第一节 ==\n我改的第一段")
                        .param("baserevid", baseRevision.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("编辑冲突")))
                .andExpect(content().string(containsString("差异对照")));
        assertEquals(2, revisionsOf("段落冲突测试页").size());
    }


    @Test
    void visualEditTabsAndVeactionRouting() throws Exception {
        insertPage("可视化入口测试页", "正文内容", false);
        MockHttpSession session = loggedInSession();

        // 阅读页：可视化「编辑」+「编辑源代码」两个页签（仿 MediaWiki）
        mockMvc.perform(get("/wiki/{title}", "可视化入口测试页").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("?veaction=edit")))
                .andExpect(content().string(containsString("编辑源代码")));

        // ?veaction=edit → 可视化编辑模式：面板随页面加载自动打开（URL 参数控制）
        mockMvc.perform(get("/wiki/{title}", "可视化入口测试页").param("veaction", "edit").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"visual-editor\"")))
                .andExpect(content().string(containsString("data-ve-auto=\"1\"")));

        // ?action=edit → 源代码编辑：面板不自动打开，但保留「编辑」页签切换入口
        mockMvc.perform(get("/wiki/{title}", "可视化入口测试页").param("action", "edit").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("?veaction=edit")))
                .andExpect(content().string(not(containsString("data-ve-auto=\"1\""))));

        // ?veaction=editsource → 回退到源代码编辑（仿 MediaWiki）
        mockMvc.perform(get("/wiki/{title}", "可视化入口测试页").param("veaction", "editsource").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(WikiUrls.pagePath("可视化入口测试页") + "?action=edit"));
    }

    @Test
    void visualEditFallsBackForCodePagesAndAnonymous() throws Exception {
        // 代码页（.js）不支持可视化编辑 → 回退源代码编辑
        insertPage("可视化回退测试.js", "// js 代码", false);
        MockHttpSession session = loggedInSession();
        mockMvc.perform(get("/wiki/{title}", "可视化回退测试.js").param("veaction", "edit").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(WikiUrls.pagePath("可视化回退测试.js") + "?action=edit"));

        // 无编辑权限（匿名）→ 同样回退
        insertPage("可视化匿名测试页", "正文", false);
        mockMvc.perform(get("/wiki/{title}", "可视化匿名测试页").param("veaction", "edit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(WikiUrls.pagePath("可视化匿名测试页") + "?action=edit"));
    }

    @Test
    void rendersAndEditsSubpageTitlesWithSlash() throws Exception {
        insertPage("子页面测试/样式.css", "子页面正文内容", false);

        mockMvc.perform(get("/wiki/子页面测试/样式.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("子页面正文内容")))
                // 链接里的 / 必须保持为路径分隔符，不能出现 %2F（Tomcat 会拒绝 %2F）
                .andExpect(content().string(containsString(
                        "/wiki/%E5%AD%90%E9%A1%B5%E9%9D%A2%E6%B5%8B%E8%AF%95/%E6%A0%B7%E5%BC%8F.css?action=edit")))
                .andExpect(content().string(not(containsString("%2F"))))
                .andExpect(content().string(not(containsString("%2f"))));

        MockHttpSession session = loggedInSession();
        // .css 页面默认只有界面管理员能编辑（见 InterfaceAdminTest）
        WikiUser interfaceAdmin = insertUser("子页面界面管理员");
        permissionService.replaceFor(interfaceAdmin.getId(), List.of(PermissionService.GROUP_INTERFACE_ADMIN));
        MockHttpSession interfaceAdminSession = new MockHttpSession();
        interfaceAdminSession.setAttribute(UserSession.ATTRIBUTE, interfaceAdmin.toTransferCopy());
        mockMvc.perform(get("/wiki/子页面测试/样式.css").param("action", "history").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("子页面测试/样式.css")));

        mockMvc.perform(post("/wiki/子页面测试/样式.css")
                        .session(interfaceAdminSession).with(csrf())
                        .param("content", "改过的子页面正文"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/wiki/%E5%AD%90%E9%A1%B5%E9%9D%A2%E6%B5%8B%E8%AF%95/%E6%A0%B7%E5%BC%8F.css"));

        mockMvc.perform(get("/wiki/子页面测试/样式.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("改过的子页面正文")));
    }

    @Test
    void editOldRevisionPreloadsItsContent() throws Exception {
        insertPage("旧版编辑测试页", "第一版内容", false);
        MockHttpSession session = loggedInSession();

        mockMvc.perform(post("/wiki/{title}", "旧版编辑测试页")
                        .session(session).with(csrf())
                        .param("content", "第二版内容"))
                .andExpect(status().is3xxRedirection());

        Long firstId = revisionsOf("旧版编辑测试页").get(0).getId();

        mockMvc.perform(get("/wiki/{title}", "旧版编辑测试页")
                        .param("action", "edit").param("oldid", firstId.toString())
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("的旧版本")))
                .andExpect(content().string(containsString("第一版内容")))
                .andExpect(content().string(not(containsString("第二版内容"))));

        mockMvc.perform(post("/wiki/{title}", "旧版编辑测试页")
                        .session(session).with(csrf())
                        .param("content", "基于旧版修改的内容"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/wiki/{title}", "旧版编辑测试页"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("基于旧版修改的内容")));
    }

    // ==================== 用户页与权限 ====================

    @Test
    void userPagesLiveInUserNamespaceWithToolsAndLegacyRedirect() throws Exception {
        WikiUser user = insertUser("权限展示用户");
        insertPage("用户:" + user.getUsername(), "用户页正文内容", false);

        mockMvc.perform(get("/wiki/{title}", "用户:" + user.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("用户页正文内容")))
                // 仿 MediaWiki：用户页不展示用户 ID / 权限标签等内部信息
                .andExpect(content().string(not(containsString("用户 ID"))))
                .andExpect(content().string(not(containsString("class=\"wiki-tag\""))))
                // 仿 MediaWiki 的「工具」工具箱
                .andExpect(content().string(containsString("链入页面")))
                .andExpect(content().string(containsString("相关更改")))
                .andExpect(content().string(containsString("用户贡献")))
                .andExpect(content().string(containsString(">日志</a>")))
                .andExpect(content().string(containsString("查看用户组")))
                .andExpect(content().string(containsString("页面信息")))
                // 用户页只读，权限编辑在专门页面
                .andExpect(content().string(not(containsString("id=\"wiki-permissions-form\""))));

        // 旧地址 特殊:用户?name=X → 302 到用户命名空间页面
        mockMvc.perform(get("/wiki/{title}", "特殊:用户").param("name", user.getUsername()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith(
                        URLEncoder.encode("用户:" + user.getUsername(), StandardCharsets.UTF_8)
                                .replace("+", "%20"))));
    }

    @Test
    void userRightsPageNeedsPermissionAndSupportsLookup() throws Exception {
        WikiUser target = insertUser("权限只读目标");
        permissionService.grantDefault(target.getId());
        // 自动用户组一行：显式授予「自动确定用户」以便验证展示（默认可通过编辑满 50 次自动获得）
        permissionService.grant(target.getId(), PermissionService.GROUP_AUTOCONFIRMED, null);

        // 不带 name：显示「选择用户」
        mockMvc.perform(get("/wiki/{title}", "特殊:用户权限"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-rights-lookup\"")))
                .andExpect(content().string(containsString("name=\"name\"")))
                .andExpect(content().string(containsString("选择用户")));

        // 普通用户打开：只读 + 自动用户组 + 空日志
        MockHttpSession session = loggedInSession();
        mockMvc.perform(get("/wiki/{title}", "特殊:用户权限").param("name", target.getUsername()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-permissions-form\"")))
                .andExpect(content().string(containsString("data-can-edit=\"false\"")))
                .andExpect(content().string(containsString("自动用户组")))
                .andExpect(content().string(containsString("自动确定用户")))
                // 显式授予「自动确定用户」会写一条权限日志
                .andExpect(content().string(containsString("已授予")))
                .andExpect(content().string(containsString("只有管理员或行政员可以修改权限")));

        // 没有任何权限变更的目标：日志区显示「不存在匹配项」
        WikiUser untouched = insertUser("权限空日志目标" + System.nanoTime());
        permissionService.grantDefault(untouched.getId());
        mockMvc.perform(get("/wiki/{title}", "特殊:用户权限")
                        .param("name", untouched.getUsername()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("在日志中不存在匹配项")));

        // 用户不存在
        mockMvc.perform(get("/wiki/{title}", "特殊:用户权限").param("name", "不存在用户xyz"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("用户不存在")));
    }

    @Test
    void adminCanUpdatePermissionsFromDedicatedPage() throws Exception {
        WikiUser admin = insertUser("权限管理员");
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        WikiUser target = insertUser("被授权用户");
        permissionService.grantDefault(target.getId());

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, admin.toTransferCopy());

        // 用户页：管理员（可修改权限）看到「更改用户组」入口（用户命名空间页面）
        insertPage("用户:" + target.getUsername(), "目标用户的用户页", false);
        mockMvc.perform(get("/wiki/{title}", "用户:" + target.getUsername()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("更改用户组")))
                .andExpect(content().string(containsString("链入页面")));

        // 专门的权限编辑页可以编辑
        mockMvc.perform(get("/wiki/{title}", "特殊:用户权限").param("name", target.getUsername()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-permissions-form\"")))
                .andExpect(content().string(containsString("data-can-edit=\"true\"")));

        // 保存权限（勾选多个，含默认勾选的自动确定用户）
        mockMvc.perform(post("/api/user/permissions")
                        .session(session)
                        .param("username", target.getUsername())
                        .param("permissions", PermissionService.GROUP_AUTOCONFIRMED)
                        .param("permissions", PermissionService.GROUP_ADMIN)
                        .param("permissions", PermissionService.GROUP_BUREAUCRAT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("权限已保存"));

        // 基础组保留，手动组替换
        assertEquals(Set.of(PermissionService.GROUP_USER, PermissionService.GROUP_AUTOCONFIRMED,
                PermissionService.GROUP_ADMIN, PermissionService.GROUP_BUREAUCRAT),
                permissionService.namesOf(target.getId()));

        // 权限页日志里能看到刚授予的记录
        mockMvc.perform(get("/wiki/{title}", "特殊:用户权限").param("name", target.getUsername()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("已授予")));
    }

    @Test
    void bureaucratSeesChangeGroupsLink() throws Exception {
        WikiUser bureaucrat = insertUser("更改组行政员" + System.nanoTime());
        permissionService.replaceFor(bureaucrat.getId(), List.of(PermissionService.GROUP_BUREAUCRAT));
        WikiUser target = insertUser("更改组目标" + System.nanoTime());

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, bureaucrat.toTransferCopy());

        // 行政员（可修改权限）：用户页工具链接是「更改用户组」，不是「查看用户组」
        mockMvc.perform(get("/wiki/{title}", "用户:" + target.getUsername()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("更改用户组")))
                .andExpect(content().string(not(containsString("查看用户组"))));
    }

    @Test
    void permissionsListPageListsUsersAndFiltersByGroup() throws Exception {
        WikiUser admin = insertUser("列表管理员");
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        WikiUser normal = insertUser("列表普通用户");
        permissionService.grantDefault(normal.getId());

        mockMvc.perform(get("/wiki/{title}", "特殊:权限列表"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("列表管理员")))
                .andExpect(content().string(containsString("列表普通用户")))
                .andExpect(content().string(containsString("rights-table")))
                .andExpect(content().string(containsString("管理员")))
                // 权限名是蓝链（链到 特殊:权限列表?group=…），不再是标签样式
                .andExpect(content().string(not(containsString("class=\"wiki-tag\""))))
                .andExpect(content().string(containsString("group=")));

        // 按权限筛选：只留下管理员
        mockMvc.perform(get("/wiki/{title}", "特殊:权限列表").param("group", "管理员"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("列表管理员")))
                .andExpect(content().string(not(containsString("列表普通用户"))));
    }

    @Test
    void anonymousOrNormalUserCannotUpdatePermissions() throws Exception {
        WikiUser target = insertUser("权限目标用户");
        permissionService.grantDefault(target.getId());

        // 匿名：未登录
        mockMvc.perform(post("/api/user/permissions")
                        .param("username", target.getUsername())
                        .param("permissions", PermissionService.GROUP_ADMIN))
                .andExpect(jsonPath("$.code").value(401));

        // 已登录但没有管理权限
        MockHttpSession session = loggedInSession();
        mockMvc.perform(post("/api/user/permissions")
                        .session(session)
                        .param("username", target.getUsername())
                        .param("permissions", PermissionService.GROUP_ADMIN))
                .andExpect(jsonPath("$.code").value(403));

        assertEquals(Set.of(PermissionService.GROUP_USER),
                permissionService.namesOf(target.getId()));
    }

    // ==================== 特殊页 / 模板 / 搜索 / 侧边栏 ====================

    @Test
    void specialPageRendersByNameAndAlias() throws Exception {
        insertPage("特殊页来源", false);

        // 中文名（用 from/to 限定范围：共享测试数据库里页面很多，不限定会翻到其它分页）
        mockMvc.perform(get("/wiki/{title}", "特殊:全部页面")
                        .param("from", "特殊页来源").param("to", "特殊页来源"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("全部页面")))
                .andExpect(content().string(containsString("特殊页来源")));
        // 英文别名
        mockMvc.perform(get("/wiki/{title}", "Special:AllPages")
                        .param("from", "特殊页来源").param("to", "特殊页来源"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("特殊页来源")));
    }

    @Test
    void unknownSpecialPageReturns404() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:不存在的特殊页"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createPageSpecialPageShowsFormAndRedirectsToEdit() throws Exception {
        // 表单页（中文名 + 英文别名）
        mockMvc.perform(get("/wiki/{title}", "特殊:创建页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-create-page-form\"")))
                .andExpect(content().string(containsString("name=\"pagename\"")));
        mockMvc.perform(get("/wiki/{title}", "Special:CreatePage"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"wiki-create-page-form\"")));

        // 空标题：留在表单并提示
        mockMvc.perform(get("/wiki/{title}", "特殊:创建页面").param("pagename", "   "))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请输入页面标题")));

        // 有标题：302 到编辑页（子页面斜杠保留、不出现 %2F）
        mockMvc.perform(get("/wiki/{title}", "特殊:创建页面").param("pagename", "沙盒测试页/子页面"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/wiki/%E6%B2%99%E7%9B%92%E6%B5%8B%E8%AF%95%E9%A1%B5/%E5%AD%90%E9%A1%B5%E9%9D%A2?action=edit"));
    }

    @Test
    void specialPagesIndexListsRegisteredPages() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:特殊页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("全部页面")))
                .andExpect(content().string(containsString("特殊页面")))
                .andExpect(content().string(containsString("创建页面")))
                // 分组排版 + 搜索框（仿 MediaWiki Special:SpecialPages）
                .andExpect(content().string(containsString("搜索特殊页面")))
                .andExpect(content().string(containsString("用户与权限")))
                .andExpect(content().string(containsString("页面工具")))
                .andExpect(content().string(containsString("其他特殊页面")))
                .andExpect(content().string(containsString("specialpages-group")))
                // 不再显示“（别名：…）”
                .andExpect(content().string(not(containsString("别名"))));
    }

    @Test
    void rendersTemplateFromDatabase() throws Exception {
        insertPage("模板:问好", "你好，{{{1|世界}}}！", false);
        insertPage("模板示例", "{{模板:问好|维基}}", false);

        mockMvc.perform(get("/wiki/{title}", "模板示例"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("你好，维基！")));
    }

    @Test
    void rendersSidebarFromPage() throws Exception {
        insertPage("WikiKernel:Sidebar", "* 导航\n** 首页\n** 特殊:特殊页面|特殊页面\n** $title$?action=info|本页信息", false);
        insertPage("侧边栏演示", "正文", false);

        mockMvc.perform(get("/wiki/{title}", "侧边栏演示"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("wiki-sidebar")))
                .andExpect(content().string(containsString("href=\"/wiki/特殊:特殊页面\"")))
                .andExpect(content().string(containsString(">特殊页面</a>")))
                // $title$ 占位替换为当前页面标题
                .andExpect(content().string(containsString("href=\"/wiki/侧边栏演示?action=info\"")));

        // 特殊页也必须使用 WikiKernel:Sidebar 的自定义内容，而不是被工具箱逻辑换成内置侧栏
        mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/wiki/特殊:特殊页面\"")))
                .andExpect(content().string(containsString(">特殊页面</a>")));

        // 错误页（没有工具箱覆盖）同样替换 $title$（标题取原始请求 URI）
        mockMvc.perform(get("/error")
                        .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/wiki/侧边栏演示"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("href=\"/wiki/侧边栏演示?action=info\"")));
    }

    @Test
    void searchesPagesFromDatabase() throws Exception {
        insertPage("苹果派", "苹果派的做法：苹果、面粉、黄油。", false);

        mockMvc.perform(get("/wiki/{title}", "特殊:搜索").param("q", "苹果"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("苹果派")));

        mockMvc.perform(get("/api/search").param("q", "苹果"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("苹果派")));
    }

    // ==================== 工具 ====================

    /** 创建一个已登录会话（用户同时写入 wiki_user，便于历史页显示用户名）。 */
    private MockHttpSession loggedInSession() {
        WikiUser user = insertUser("编辑测试用户" + System.nanoTime());

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }

    /** 按标题（测试内唯一）查询该页面的全部版本，按版本 ID 升序。 */
    private List<WikiRevision> revisionsOf(String title) {
        WikiPage page = wikiPageMapper.selectOne(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, title));
        return wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .eq(WikiRevision::getPageId, page.getId())
                .orderByAsc(WikiRevision::getId));
    }

    private void insertPage(String title, boolean hided) {
        insertPage(title, "这是正文", hided);
    }

    /** 插入「页面 + 初始版本」两行数据。 */
    private void insertPage(String title, String content, boolean hided) {
        WikiPage page = new WikiPage();
        page.setTitle(title);
        page.setHided(hided);
        page.setDeleted(false);
        page.setCreateAt(TEST_TIME);
        page.setUpdateAt(TEST_TIME);
        wikiPageMapper.insert(page);

        WikiRevision revision = new WikiRevision();
        revision.setPageId(page.getId());
        revision.setContent(content);
        revision.setDeleted(false);
        revision.setCreateAt(TEST_TIME);
        wikiRevisionMapper.insert(revision);

        page.setLatestRevisionId(revision.getId());
        wikiPageMapper.updateById(page);
    }

    /** 追加一个版本并推进页面最新版本（模拟「另一个人先保存」）。 */
    private void appendRevision(WikiPage page, String content) {
        WikiRevision revision = new WikiRevision();
        revision.setPageId(page.getId());
        revision.setContent(content);
        revision.setDeleted(false);
        revision.setCreateAt(TEST_TIME.plusMinutes(1));
        wikiRevisionMapper.insert(revision);

        page.setLatestRevisionId(revision.getId());
        page.setUpdateAt(TEST_TIME.plusMinutes(1));
        wikiPageMapper.updateById(page);
    }
}
