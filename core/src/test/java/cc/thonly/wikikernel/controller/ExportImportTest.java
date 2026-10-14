package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiAccessRule;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 页面导出 / 导入（特殊:导出页面 / 特殊:导入页面）：
 * <ul>
 *     <li>三种导出格式（MediaWiki XML / WikiKernel XML / WikiKernel JSON）与完整历史；</li>
 *     <li>导出按查看权限过滤（无权页面不出现、不泄露标题）；「包含模板」递归带出模板页；</li>
 *     <li>导入自动识别三种格式；新建页面 / 追加历史 / 贡献者关联 / 时间保留；</li>
 *     <li>重复导入幂等（正文 sha1 + 时间去重）；只有管理员 / 行政员能导入。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class ExportImportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private LogService logService;

    @Autowired
    private AccessControlService accessControlService;

    // ==================== 导出 ====================

    @Test
    void exportMediaWikiXmlContainsFullHistory() throws Exception {
        WikiUser admin = adminUser();
        String title = "导出测试页" + System.nanoTime();
        wikiPageService.editPage(admin, title, "第一版", "创建");
        wikiPageService.editPage(admin, title, "第二版", "修改");

        MvcResult result = mockMvc.perform(post("/api/export").with(csrf())
                        .param("pages", title)
                        .param("history", "1")
                        .param("format", "mediawiki")
                        .param("download", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Type", containsString("xml")))
                .andReturn();

        String xml = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(xml.contains("<mediawiki"), "应是 MediaWiki 导出格式");
        assertTrue(xml.contains("<title>" + title + "</title>"), "应包含标题");
        assertTrue(xml.contains("第一版") && xml.contains("第二版"), "应包含两个版本");
        assertEquals(2, occurrences(xml, "<revision>"), "应导出 2 个版本");
        assertTrue(xml.contains("sha1="), "正文应带 sha1");
        assertTrue(xml.contains("<contributor>"), "登录用户的编辑应带贡献者");
    }

    @Test
    void exportOptionallyIncludesTemplates() throws Exception {
        WikiUser admin = adminUser();
        String template = "模板:导出模板" + System.nanoTime();
        String page = "导出模板页" + System.nanoTime();
        wikiPageService.editPage(admin, template, "模板内容");
        wikiPageService.editPage(admin, page, "{{" + template + "}}");

        String without = export(page, "mediawiki", true, false);
        assertFalse(without.contains("<title>" + template + "</title>"), "不勾选时不应带出模板");

        String with = export(page, "mediawiki", true, true);
        assertTrue(with.contains("<title>" + template + "</title>"), "勾选后应带出被引用的模板");
        assertTrue(with.contains("模板内容"));
    }

    @Test
    void exportSkipsPagesTheUserCannotRead() throws Exception {
        WikiUser admin = adminUser();
        String title = "导出秘页面" + System.nanoTime();
        wikiPageService.editPage(admin, title, "秘密内容");
        WikiAccessRule rule = accessControlService.saveRule(title, AccessAction.READ, "");
        try {
            // 匿名导出：无权查看 → 标题与内容都不出现
            String xml = mockMvc.perform(get("/api/export")
                            .param("pages", title)
                            .param("history", "1")
                            .param("format", "mediawiki"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertFalse(xml.contains(title), "无权查看的标题不应泄露");
            assertFalse(xml.contains("秘密内容"), "无权查看的内容不应泄露");
        } finally {
            accessControlService.deleteRule(rule.getId());
        }
    }

    // ==================== 导入 ====================

    @Test
    void importMediaWikiXmlCreatesPageAndIsIdempotent() throws Exception {
        WikiUser admin = adminUser();
        String editorName = "导入编辑者" + System.nanoTime();
        WikiUser editor = insertUser(editorName);
        String title = "导入测试页" + System.nanoTime();
        String xml = """
                <mediawiki xmlns="http://www.mediawiki.org/xml/export-0.11/" version="0.11">
                  <siteinfo><sitename>Test</sitename></siteinfo>
                  <page>
                    <title>%s</title><ns>0</ns><id>1</id>
                    <revision><id>1</id><timestamp>2020-01-02T03:04:05Z</timestamp>
                      <contributor><username>%s</username><id>9</id></contributor>
                      <comment>导入摘要一</comment><model>wikitext</model><format>text/x-wiki</format>
                      <text bytes="15" xml:space="preserve">内容一</text></revision>
                    <revision><id>2</id><parentid>1</parentid><timestamp>2020-01-03T03:04:05Z</timestamp>
                      <contributor><username>%s</username><id>9</id></contributor>
                      <comment>导入摘要二</comment><model>wikitext</model><format>text/x-wiki</format>
                      <text bytes="15" xml:space="preserve">内容二</text></revision>
                  </page>
                </mediawiki>
                """.formatted(title, editorName, editorName);
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/api/import")
                        .file(new MockMultipartFile("file", "export.xml", "text/xml", bytes))
                        .session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pagesCreated").value(1))
                .andExpect(jsonPath("$.data.revisionsImported").value(2));

        WikiPage page = wikiPageService.getByTitle(title).orElseThrow();
        assertEquals("内容二", wikiPageService.getLatestContent(page.getId()).orElseThrow());
        List<WikiRevision> history = wikiPageService.getHistory(page.getId());
        assertEquals(2, history.size());
        assertTrue(history.stream().allMatch(revision -> editor.getId().equals(revision.getUserId())),
                "贡献者用户名应关联到本地账号");
        assertTrue(history.stream().allMatch(revision -> revision.getCreateAt().getYear() == 2020),
                "版本时间应沿用导出文件里的时间");
        assertTrue(logService.recent("import", null, false, 20).stream()
                        .anyMatch(log -> title.equals(log.getTarget())),
                "导入应写日志");

        // 重复导入：不产生新版本（幂等）
        mockMvc.perform(multipart("/api/import")
                        .file(new MockMultipartFile("file", "export.xml", "text/xml", bytes))
                        .session(sessionOf(admin)))
                .andExpect(jsonPath("$.data.pagesCreated").value(0))
                .andExpect(jsonPath("$.data.pagesSkipped").value(1))
                .andExpect(jsonPath("$.data.revisionsImported").value(0))
                .andExpect(jsonPath("$.data.revisionsSkipped").value(2));
        assertEquals(2, wikiPageService.getHistory(page.getId()).size(), "重复导入不应新增版本");
    }

    @Test
    void importWikiKernelJsonPreservesLocalFlags() throws Exception {
        WikiUser admin = adminUser();
        String editorName = "JSON编辑者" + System.nanoTime();
        WikiUser editor = insertUser(editorName);
        String title = "导入JSON页" + System.nanoTime();
        String json = """
                {
                  "format": "wikikernel",
                  "version": 1,
                  "pages": [
                    {
                      "title": "%s",
                      "ns": 0,
                      "protected": true,
                      "hided": false,
                      "deleted": false,
                      "revisions": [
                        {"timestamp": "2021-05-06T07:08:09+08:00", "user": "%s", "bot": true,
                         "deleted": false, "tags": "测试标签", "comment": "机器人导入", "content": "JSON 内容"}
                      ]
                    }
                  ]
                }
                """.formatted(title, editorName);

        mockMvc.perform(multipart("/api/import")
                        .file(new MockMultipartFile("file", "export.json", "application/json",
                                json.getBytes(StandardCharsets.UTF_8)))
                        .session(sessionOf(admin)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pagesCreated").value(1));

        WikiPage page = wikiPageService.getByTitle(title).orElseThrow();
        assertTrue(Boolean.TRUE.equals(page.getProtectedPage()), "保护状态应被保留");
        assertEquals("JSON 内容", wikiPageService.getLatestContent(page.getId()).orElseThrow());
        WikiRevision revision = wikiPageService.getLatestRevision(page.getId()).orElseThrow();
        assertTrue(Boolean.TRUE.equals(revision.getBot()), "机器人标记应被保留");
        assertTrue(revision.getTags().contains("测试标签"), "标签应被保留");
        assertEquals(editor.getId(), revision.getUserId(), "用户名应关联本地账号");
        assertEquals(2021, revision.getCreateAt().getYear(), "时间应正确解析（+08:00 偏移）");
    }

    @Test
    void exportedFilesCanBeReimportedWithoutDuplicates() throws Exception {
        WikiUser admin = adminUser();
        String title = "往返导出页" + System.nanoTime();
        // 第一版故意用 CRLF 换行：XML 解析器会归一化成 LF，去重指纹必须同样归一化
        wikiPageService.editPage(admin, title, "往返一版\r\n第二行", "创建");
        wikiPageService.editPage(admin, title, "往返二版\n第二行", "修改");

        for (String format : List.of("wikikernel-json", "wikikernel-xml", "mediawiki")) {
            byte[] bytes = mockMvc.perform(post("/api/export").with(csrf())
                            .param("pages", title)
                            .param("history", "1")
                            .param("format", format)
                            .param("download", "1"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            String extension = format.endsWith("json") ? "json" : "xml";
            mockMvc.perform(multipart("/api/import")
                            .file(new MockMultipartFile("file", "roundtrip." + extension,
                                    "application/octet-stream", bytes))
                            .session(sessionOf(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.revisionsImported").value(0));
        }
        WikiPage page = wikiPageService.getByTitle(title).orElseThrow();
        assertEquals(2, wikiPageService.getHistory(page.getId()).size(),
                "三种格式往返导入都不应产生重复版本");
    }

    @Test
    void importRequiresManagerAndRejectsUnknownFormat() throws Exception {
        // 匿名打开导入页：403
        mockMvc.perform(get("/wiki/{title}", "特殊:导入页面"))
                .andExpect(status().isForbidden());

        // 匿名 API：401（JSON code）
        mockMvc.perform(multipart("/api/import")
                        .file(new MockMultipartFile("file", "x.xml", "text/xml", "<x/>".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        // 普通用户：403
        WikiUser user = normalUser();
        mockMvc.perform(multipart("/api/import")
                        .file(new MockMultipartFile("file", "x.xml", "text/xml", "<x/>".getBytes(StandardCharsets.UTF_8)))
                        .session(sessionOf(user)))
                .andExpect(jsonPath("$.code").value(403));

        // 管理员：无法识别的格式 → 400
        WikiUser admin = adminUser();
        mockMvc.perform(multipart("/api/import")
                        .file(new MockMultipartFile("file", "x.txt", "text/plain",
                                "hello".getBytes(StandardCharsets.UTF_8)))
                        .session(sessionOf(admin)))
                .andExpect(jsonPath("$.code").value(400));
    }

    // ==================== 工具 ====================

    private String export(String title, String format, boolean history, boolean templates) throws Exception {
        return mockMvc.perform(post("/api/export").with(csrf())
                        .param("pages", title)
                        .param("history", history ? "1" : "0")
                        .param("templates", templates ? "1" : "0")
                        .param("format", format)
                        .param("download", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String needle) {
        return text.split(Pattern.quote(needle), -1).length - 1;
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }

    private WikiUser normalUser() {
        return insertUser("导入测试用户" + System.nanoTime());
    }

    private WikiUser adminUser() {
        WikiUser user = insertUser("导入测试管理员" + System.nanoTime());
        permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_ADMIN));
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
