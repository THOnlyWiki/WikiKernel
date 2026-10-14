package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MediaWiki 兼容 Action API（{@code /api.php}）：
 * siteinfo / query（revisions、content、allpages、search、categorymembers、recentchanges、users）、
 * parse、token 流程、login、edit（含 nochange / badtoken）、opensearch、错误格式。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class ActionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private FileService fileService;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== query ====================

    @Test
    void siteinfoAndUserinfo() throws Exception {
        mockMvc.perform(get("/api.php").param("action", "query")
                        .param("meta", "siteinfo")
                        .param("formatversion", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.general.sitename").value("WikiKernel"))
                .andExpect(jsonPath("$.query.general.mainpage").value("首页"))
                .andExpect(jsonPath("$.query.namespaces.0.name").value(""))
                .andExpect(jsonPath("$.query.namespaces.10.name").value("模板"))
                .andExpect(jsonPath("$.batchcomplete").value(true));

        mockMvc.perform(get("/api.php").param("action", "query")
                        .param("meta", "userinfo"))
                .andExpect(jsonPath("$.query.userinfo.id").value(0))
                .andExpect(jsonPath("$.query.userinfo.anon").value(true));
    }

    @Test
    void queryRevisionsWithContentV1AndV2() throws Exception {
        String title = "API内容页" + System.nanoTime();
        wikiPageService.editPage(null, title, "第一版内容");
        wikiPageService.editPage(null, title, "第二版内容");
        long pageId = wikiPageService.getByTitle(title).orElseThrow().getId();

        // formatversion=1：pages 是以 pageid 为键的对象，内容在 "*"（JsonPath 里用 ['*'] 取字面键）
        mockMvc.perform(get("/api.php").param("action", "query")
                        .param("prop", "revisions")
                        .param("rvprop", "ids|timestamp|user|comment|content")
                        .param("rvlimit", "5")
                        .param("titles", title))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['query']['pages']['" + pageId + "']['revisions'][0]['*']")
                        .value("第二版内容"))
                .andExpect(jsonPath("$['query']['pages']['" + pageId + "']['revisions'][1]['*']")
                        .value("第一版内容"));

        // formatversion=2：pages 是数组，内容在 content
        mockMvc.perform(get("/api.php").param("action", "query")
                        .param("prop", "revisions")
                        .param("rvprop", "ids|timestamp|content")
                        .param("rvlimit", "5")
                        .param("titles", title)
                        .param("formatversion", "2"))
                .andExpect(jsonPath("$.query.pages[0].revisions[0].content").value("第二版内容"))
                .andExpect(jsonPath("$.query.pages[0].revisions[0].revid").isNumber());
    }

    @Test
    void allpagesSearchCategoryMembersAndUsers() throws Exception {
        String title = "API列表演示" + System.nanoTime();
        wikiPageService.editPage(null, title, "搜索关键词：压测演示ABC123\n[[分类:API测试]]");
        WikiUser user = insertUser("API列表用户" + System.nanoTime());

        mockMvc.perform(get("/api.php").param("action", "query")
                        .param("list", "search").param("srsearch", "压测演示ABC123"))
                .andExpect(jsonPath("$.query.search[0].title").value(title));

        mockMvc.perform(get("/api.php").param("action", "query")
                        .param("list", "allpages").param("apnamespace", "0").param("aplimit", "500"))
                .andExpect(content().string(containsString(title)));

        mockMvc.perform(get("/api.php").param("action", "query")
                        .param("list", "categorymembers").param("cmtitle", "分类:API测试")
                        .param("cmlimit", "100"))
                .andExpect(content().string(containsString(title)));

        mockMvc.perform(get("/api.php").param("action", "query")
                        .param("list", "users").param("ususers", "API列表用户不存在"))
                .andExpect(jsonPath("$.query.users[0].missing").value(""));

        mockMvc.perform(get("/api.php").param("action", "query")
                        .param("list", "users").param("ususers", user.getUsername()))
                .andExpect(jsonPath("$.query.users[0].userid").value(user.getId()));
    }

    @Test
    void parseWikitextAndText() throws Exception {
        String title = "API解析页" + System.nanoTime();
        wikiPageService.editPage(null, title, "解析正文'''加粗'''");

        mockMvc.perform(get("/api.php").param("action", "parse")
                        .param("page", title).param("prop", "wikitext|text")
                        .param("formatversion", "2"))
                .andExpect(jsonPath("$.parse.wikitext").value("解析正文'''加粗'''"))
                .andExpect(jsonPath("$.parse.text", containsString("<b>加粗</b>")))
                .andExpect(jsonPath("$.parse.title").value(title));
    }

    @Test
    void recentChangesAndLogEvents() throws Exception {
        WikiUser user = insertUser("API动态用户" + System.nanoTime());
        String title = "API动态页" + System.nanoTime();
        wikiPageService.editPage(user, title, "动态内容");

        mockMvc.perform(get("/api.php").param("action", "query")
                        .param("list", "recentchanges")
                        .param("rcprop", "title|ids|sizes|user|timestamp|comment")
                        .param("rclimit", "50"))
                .andExpect(jsonPath("$.query.recentchanges[0]").exists())
                .andExpect(content().string(containsString(title)));

        mockMvc.perform(get("/api.php").param("action", "query")
                        .param("list", "logevents").param("lelimit", "20"))
                .andExpect(jsonPath("$.query.logevents[0]").exists());
    }

    // ==================== token / login / edit ====================

    @Test
    void editRequiresLoginTokenAndWorks() throws Exception {
        // 匿名编辑：notloggedin
        mockMvc.perform(post("/api.php").param("action", "edit")
                        .param("title", "API编辑页").param("text", "x").param("token", "+\\"))
                .andExpect(jsonPath("$.error.code").value("notloggedin"));

        // 登录（token 流程）
        WikiUser user = insertUser("API编辑用户" + System.nanoTime(), "secret123");
        MockHttpSession session = new MockHttpSession();
        String loginToken = json(mockMvc.perform(get("/api.php").session(session)
                        .param("action", "query").param("meta", "tokens").param("type", "login"))
                .andReturn()).path("query").path("tokens").path("logintoken").asString();
        assertFalse(loginToken.isEmpty());

        JsonNode login = json(mockMvc.perform(post("/api.php").session(session)
                        .param("action", "login")
                        .param("lgname", user.getUsername()).param("lgpassword", "secret123")
                        .param("lgtoken", loginToken))
                .andReturn());
        assertEquals("Success", login.path("login").path("result").asString());

        // 无 token 编辑：badtoken
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "edit").param("title", "API编辑页")
                        .param("text", "第一版").param("token", "wrong"))
                .andExpect(jsonPath("$.error.code").value("badtoken"));

        // 取 csrf token 后编辑成功
        String csrf = json(mockMvc.perform(get("/api.php").session(session)
                        .param("action", "query").param("meta", "tokens").param("type", "csrf"))
                .andReturn()).path("query").path("tokens").path("csrftoken").asString();
        String title = "API编辑页" + System.nanoTime();
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "edit").param("title", title)
                        .param("text", "第一版").param("summary", "API 创建")
                        .param("token", csrf))
                .andExpect(jsonPath("$.edit.result").value("Success"))
                .andExpect(jsonPath("$.edit.newrevid").isNumber());
        assertEquals("第一版", wikiPageService.getLatestContent(
                wikiPageService.getByTitle(title).orElseThrow().getId()).orElseThrow());

        // 内容没变：nochange
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "edit").param("title", title)
                        .param("text", "第一版").param("token", csrf))
                .andExpect(jsonPath("$.edit.result").value("Success"))
                .andExpect(jsonPath("$.edit.nochange").value(""));

        // appendtext
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "edit").param("title", title)
                        .param("appendtext", "\n追加一行").param("token", csrf))
                .andExpect(jsonPath("$.edit.result").value("Success"));
        assertTrue(wikiPageService.getLatestContent(
                wikiPageService.getByTitle(title).orElseThrow().getId()).orElseThrow().contains("追加一行"));

        // userinfo 显示当前用户
        mockMvc.perform(get("/api.php").session(session)
                        .param("action", "query").param("meta", "userinfo"))
                .andExpect(jsonPath("$.query.userinfo.name").value(user.getUsername()));
    }

    @Test
    void loginWrongPasswordAndLogout() throws Exception {
        WikiUser user = insertUser("API登录用户" + System.nanoTime(), "secret123");
        MockHttpSession session = new MockHttpSession();
        String token = json(mockMvc.perform(get("/api.php").session(session)
                        .param("action", "query").param("meta", "tokens").param("type", "login"))
                .andReturn()).path("query").path("tokens").path("logintoken").asString();
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "login").param("lgname", user.getUsername())
                        .param("lgpassword", "wrong-password").param("lgtoken", token))
                .andExpect(jsonPath("$.login.result").value("Failed"))
                .andExpect(jsonPath("$.login.reason").value("wrongpassword"));

        // NeedToken（不带 token）
        mockMvc.perform(post("/api.php").session(new MockHttpSession())
                        .param("action", "login").param("lgname", user.getUsername())
                        .param("lgpassword", "secret123"))
                .andExpect(jsonPath("$.login.result").value("NeedToken"));
    }

    // ==================== 其它 ====================

    @Test
    void opensearchUnknownActionAndJsonfm() throws Exception {
        mockMvc.perform(get("/api.php").param("action", "opensearch")
                        .param("search", "首页"))
                .andExpect(jsonPath("$[0]").value("首页"))
                .andExpect(jsonPath("$[1]").isArray());

        mockMvc.perform(get("/api.php").param("action", "no-such-action"))
                .andExpect(jsonPath("$.error.code").value("unknown_action"));

        mockMvc.perform(get("/api.php").param("action", "query").param("meta", "siteinfo")
                        .param("format", "jsonfm"))
                .andExpect(content().string(containsString("<pre>")));
    }

    @Test
    void editRejectsInvalidTitle() throws Exception {
        WikiUser user = insertUser("API非法标题用户" + System.nanoTime(), "secret123");
        MockHttpSession session = new MockHttpSession();
        String loginToken = json(mockMvc.perform(get("/api.php").session(session)
                        .param("action", "query").param("meta", "tokens").param("type", "login"))
                .andReturn()).path("query").path("tokens").path("logintoken").asString();
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "login").param("lgname", user.getUsername())
                        .param("lgpassword", "secret123").param("lgtoken", loginToken))
                .andExpect(jsonPath("$.login.result").value("Success"));
        String csrf = json(mockMvc.perform(get("/api.php").session(session)
                        .param("action", "query").param("meta", "tokens").param("type", "csrf"))
                .andReturn()).path("query").path("tokens").path("csrftoken").asString();

        // 非法字符（# < > [ ] | { } 等）：仿 MediaWiki 返回 invalidtitle
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "edit").param("title", "非法[标题" + System.nanoTime())
                        .param("text", "x").param("token", csrf))
                .andExpect(jsonPath("$.error.code").value("invalidtitle"));

        // 合法标题正常保存（首字母大写也会写进规范标题）
        String lowercase = "apiCase" + System.nanoTime() + " 页";
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "edit").param("title", lowercase)
                        .param("text", "api 大小写内容").param("token", csrf))
                .andExpect(jsonPath("$.edit.result").value("Success"))
                .andExpect(jsonPath("$.edit.title").value(Character.toUpperCase(lowercase.charAt(0))
                        + lowercase.substring(1)));
    }

    // ==================== 管理动作（move / delete / protect / block / upload / 段落） ====================

    @Test
    void managementActionsWork() throws Exception {
        WikiUser admin = insertUser("API管理用户" + System.nanoTime(), "secret123");
        permissionService.grant(admin.getId(), PermissionService.GROUP_ADMIN, null);
        MockHttpSession session = apiLogin(admin.getUsername(), "secret123");
        String csrf = csrfToken(session);

        String from = "API移动源" + System.nanoTime();
        String to = "API移动目标" + System.nanoTime();
        wikiPageService.editPage(admin, from, "移动内容");

        // move：默认在旧标题留下重定向
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "move").param("from", from).param("to", to)
                        .param("reason", "测试移动").param("token", csrf))
                .andExpect(jsonPath("$.move.to").value(to))
                .andExpect(jsonPath("$.move.redirectcreated").value(true));
        assertTrue(wikiPageService.getByTitle(to).isPresent());
        assertTrue(wikiPageService.getByTitle(from).isPresent(), "默认留下重定向");

        // delete：软删除目标页
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "delete").param("title", to)
                        .param("reason", "测试删除").param("token", csrf))
                .andExpect(jsonPath("$.delete.title").value(to));
        assertTrue(wikiPageService.getByTitle(to).isEmpty());

        // protect / 解除保护
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "protect").param("title", from)
                        .param("protections", "edit=sysop").param("token", csrf))
                .andExpect(jsonPath("$.protect.protections").value("edit=sysop"));
        assertTrue(wikiPageService.getByTitle(from).orElseThrow().getProtectedPage());
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "protect").param("title", from)
                        .param("protections", "edit=all").param("token", csrf));
        assertFalse(wikiPageService.getByTitle(from).orElseThrow().getProtectedPage());

        // block / unblock
        WikiUser target = insertUser("API封禁目标" + System.nanoTime(), "secret123");
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "block").param("user", target.getUsername())
                        .param("reason", "测试封禁").param("expiry", "1d").param("token", csrf))
                .andExpect(jsonPath("$.block.user").value(target.getUsername()))
                .andExpect(jsonPath("$.block.expiry").value("1d"));
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "unblock").param("user", target.getUsername()).param("token", csrf))
                .andExpect(jsonPath("$.unblock.user").value(target.getUsername()));

        // 未登录：notloggedin
        mockMvc.perform(post("/api.php").param("action", "move")
                        .param("from", from).param("to", "X"))
                .andExpect(jsonPath("$.error.code").value("notloggedin"));

        // 目标已存在：articleexists
        wikiPageService.editPage(admin, to, "又建一页");
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "move").param("from", from).param("to", to).param("token", csrf))
                .andExpect(jsonPath("$.error.code").value("articleexists"));

        // 自移动：selfmove
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "move").param("from", from).param("to", from).param("token", csrf))
                .andExpect(jsonPath("$.error.code").value("selfmove"));
    }

    @Test
    void editSectionsAndUploadWork() throws Exception {
        WikiUser user = insertUser("API段落用户" + System.nanoTime(), "secret123");
        MockHttpSession session = apiLogin(user.getUsername(), "secret123");
        String csrf = csrfToken(session);
        String title = "API段落页" + System.nanoTime();
        mockMvc.perform(post("/api.php").session(session).param("action", "edit")
                        .param("title", title)
                        .param("text", "导语\n== 第一段 ==\n旧内容\n== 第二段 ==\n二").param("token", csrf))
                .andExpect(jsonPath("$.edit.result").value("Success"));

        // section=1：只替换第一段，其余逐字保留
        mockMvc.perform(post("/api.php").session(session).param("action", "edit")
                        .param("title", title).param("section", "1")
                        .param("text", "== 第一段 ==\n新内容").param("token", csrf))
                .andExpect(jsonPath("$.edit.result").value("Success"));
        String content = wikiPageService.getLatestContent(
                wikiPageService.getByTitle(title).orElseThrow().getId()).orElseThrow();
        assertTrue(content.contains("新内容"));
        assertFalse(content.contains("旧内容"));
        assertTrue(content.contains("二"), "其它段落保留");

        // section=new：追加新段落
        mockMvc.perform(post("/api.php").session(session).param("action", "edit")
                        .param("title", title).param("section", "new").param("sectiontitle", "新段落")
                        .param("text", "追加内容").param("token", csrf))
                .andExpect(jsonPath("$.edit.result").value("Success"));
        content = wikiPageService.getLatestContent(
                wikiPageService.getByTitle(title).orElseThrow().getId()).orElseThrow();
        assertTrue(content.contains("== 新段落 =="));
        assertTrue(content.contains("追加内容"));

        // 不存在的段落
        mockMvc.perform(post("/api.php").session(session).param("action", "edit")
                        .param("title", title).param("section", "99").param("text", "x").param("token", csrf))
                .andExpect(jsonPath("$.error.code").value("nosuchsection"));

        // upload（multipart，字段 file）
        String fileName = "API上传图" + System.nanoTime() + ".png";
        MvcResult uploadResult = mockMvc.perform(multipart("/api.php")
                        .file(new MockMultipartFile("file", fileName, "image/png", new byte[]{1, 2, 3, 4}))
                        .param("action", "upload").param("filename", fileName)
                        .param("comment", "API 上传").param("token", csrf).session(session))
                .andReturn();
        String uploadBody = uploadResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(uploadBody.contains("\"Success\""), uploadBody);
        assertTrue(uploadBody.contains(fileName), uploadBody);
        assertTrue(fileService.findByName(fileName).isPresent(), uploadBody);

        // 缺文件
        mockMvc.perform(multipart("/api.php")
                        .param("action", "upload").param("token", csrf).session(session))
                .andExpect(jsonPath("$.error.code").value("nofile"));
    }

    private MockHttpSession apiLogin(String username, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String loginToken = json(mockMvc.perform(get("/api.php").session(session)
                        .param("action", "query").param("meta", "tokens").param("type", "login"))
                .andReturn()).path("query").path("tokens").path("logintoken").asString();
        mockMvc.perform(post("/api.php").session(session)
                        .param("action", "login").param("lgname", username)
                        .param("lgpassword", password).param("lgtoken", loginToken))
                .andExpect(jsonPath("$.login.result").value("Success"));
        return session;
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        return json(mockMvc.perform(get("/api.php").session(session)
                        .param("action", "query").param("meta", "tokens").param("type", "csrf"))
                .andReturn()).path("query").path("tokens").path("csrftoken").asString();
    }

    @Test
    void compareRollbackRedirectsAndXmlWork() throws Exception {
        // compare（两个版本）
        String title = "API比较页" + System.nanoTime();
        wikiPageService.editPage(null, title, "旧内容");
        Long firstRevision = wikiPageService.getByTitle(title).orElseThrow().getLatestRevisionId();
        wikiPageService.editPage(null, title, "新内容");
        Long secondRevision = wikiPageService.getByTitle(title).orElseThrow().getLatestRevisionId();
        mockMvc.perform(get("/api.php").param("action", "compare")
                        .param("fromrev", String.valueOf(firstRevision))
                        .param("torev", String.valueOf(secondRevision)).param("formatversion", "2"))
                .andExpect(jsonPath("$.compare.torevid").value(secondRevision))
                .andExpect(content().string(containsString("diff-addedline")));

        // redirects=1：跟随重定向
        String target = "API重定向目标" + System.nanoTime();
        String redirect = "API重定向来源" + System.nanoTime();
        wikiPageService.editPage(null, target, "目标内容");
        wikiPageService.editPage(null, redirect, "#REDIRECT [[" + target + "]]");
        mockMvc.perform(get("/api.php").param("action", "query").param("titles", redirect)
                        .param("redirects", "1").param("formatversion", "2"))
                .andExpect(jsonPath("$.query.pages[0].title").value(target))
                .andExpect(jsonPath("$.query.redirects[0].from").value(redirect))
                .andExpect(jsonPath("$.query.redirects[0].to").value(target));

        // format=xml：简化 XML 输出
        mockMvc.perform(get("/api.php").param("action", "query").param("meta", "siteinfo")
                        .param("format", "xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<api>")))
                .andExpect(content().string(containsString("<sitename>")));
        mockMvc.perform(get("/api.php").param("action", "no-such-action").param("format", "xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<code>unknown_action</code>")));

        // rollback：需要管理员；回退目标用户的连续编辑
        WikiUser admin = insertUser("API回退管理员" + System.nanoTime(), "secret123");
        permissionService.grant(admin.getId(), PermissionService.GROUP_ADMIN, null);
        WikiUser author = insertUser("API回退作者" + System.nanoTime(), "secret123");
        MockHttpSession adminSession = apiLogin(admin.getUsername(), "secret123");
        String adminCsrf = csrfToken(adminSession);
        String rollbackTitle = "API回退页" + System.nanoTime();
        wikiPageService.editPage(null, rollbackTitle, "初始版");
        wikiPageService.editPage(author, rollbackTitle, "作者第一版");
        wikiPageService.editPage(author, rollbackTitle, "作者第二版");
        mockMvc.perform(post("/api.php").session(adminSession)
                        .param("action", "rollback").param("title", rollbackTitle)
                        .param("user", author.getUsername()).param("token", adminCsrf))
                .andExpect(jsonPath("$.rollback.title").value(rollbackTitle))
                .andExpect(jsonPath("$.rollback.revid").isNumber());
        assertEquals("初始版", wikiPageService.getLatestContent(
                wikiPageService.getByTitle(rollbackTitle).orElseThrow().getId()).orElseThrow());

        // 非管理员：permissiondenied
        WikiUser normal = insertUser("API回退普通" + System.nanoTime(), "secret123");
        MockHttpSession normalSession = apiLogin(normal.getUsername(), "secret123");
        String normalCsrf = csrfToken(normalSession);
        mockMvc.perform(post("/api.php").session(normalSession)
                        .param("action", "rollback").param("title", rollbackTitle).param("token", normalCsrf))
                .andExpect(jsonPath("$.error.code").value("permissiondenied"));
    }

    // ==================== 工具 ====================

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private WikiUser insertUser(String username) {
        return insertUser(username, "x");
    }

    private WikiUser insertUser(String username, String password) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword(cc.thonly.wikikernel.user.MediaWikiPassword.hash(password));
        user.setEmail("");
        wikiUserMapper.insert(user);
        permissionService.grantDefault(user.getId());
        return user;
    }
}
