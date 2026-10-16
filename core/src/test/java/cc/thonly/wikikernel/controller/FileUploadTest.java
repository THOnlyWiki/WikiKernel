package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.web.WikiUrls;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.FileService;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件上传 / 权限 / 预览：默认登录用户可上传、访问规则可收紧（文件:* 前缀 + 文件:<name> 精确），
 * 词条里 {@code [[文件:xxx]]} 嵌入图片，删除需要 delete 动作（默认管理员）。
 * <p>
 * 文件页在 {@code 文件:<名>}（仿 MediaWiki File 命名空间）；同名重复上传 = 新版本（可回滚），
 * 删除默认软删除（回收站可恢复），勾选彻底删除时连同历史版本一起清掉。
 * <p>
 * 独立上下文：上传目录改用 {@code ./build/test-files}，不污染运行目录。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.run-path=./build/test-files"
})
@AutoConfigureMockMvc
class FileUploadTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    /** 第二版内容（文件存储不校验内容，只按扩展名给 MIME，这里只要字节不同即可）。 */
    private static final byte[] PNG_V2 = "second-version-bytes".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FileService fileService;

    @Autowired
    private AccessControlService accessControlService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void uploadRequiresLoginAndFollowsUploadRule() throws Exception {
        WikiUser user = insertUser("上传用户" + System.nanoTime());
        String name = "上传测试" + System.nanoTime() + ".png";

        // 匿名：401
        mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", name, "image/png", PNG)))
                .andExpect(jsonPath("$.code").value(401));

        // 默认规则（登录用户）：200，并可通过 /files/<storedName> 访问
        mockMvc.perform(multipart("/api/files/upload").session(sessionOf(user))
                        .file(new MockMultipartFile("file", name, "image/png", PNG)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("文件已上传"))
                .andExpect(jsonPath("$.data.url").exists())
                .andExpect(jsonPath("$.data.pageUrl").value(WikiUrls.pagePath("文件:" + name)));

        WikiFile file = fileService.findByName(name).orElseThrow();
        mockMvc.perform(get("/files/{stored}", file.getStoredName()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG));

        // 收紧：文件:* 上传需要管理员 → 普通用户 403，管理员 200
        accessControlService.saveRule("文件:*", AccessAction.UPLOAD, PermissionService.GROUP_ADMIN);
        String blocked = "上传受限" + System.nanoTime() + ".png";
        mockMvc.perform(multipart("/api/files/upload").session(sessionOf(user))
                        .file(new MockMultipartFile("file", blocked, "image/png", PNG)))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("您没有权限上传文件。"));

        WikiUser admin = admin("上传管理员" + System.nanoTime());
        mockMvc.perform(multipart("/api/files/upload").session(sessionOf(admin))
                        .file(new MockMultipartFile("file", blocked, "image/png", PNG)))
                .andExpect(jsonPath("$.code").value(200));

        // 测试上下文共享内存库：清掉 文件:* 规则，避免影响其它用例（默认=登录用户）
        accessControlService.findRule(AccessAction.UPLOAD, "文件:*")
                .ifPresent(rule -> accessControlService.deleteRule(rule.getId()));
    }

    @Test
    void fileReadRuleHidesFileAndGallery() throws Exception {
        WikiUser admin = admin("文件读管理员" + System.nanoTime());
        String name = "机密图" + System.nanoTime() + ".png";
        upload(admin, name);
        WikiFile file = fileService.findByName(name).orElseThrow();

        // 默认所有人可看
        mockMvc.perform(get("/files/{stored}", file.getStoredName()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG))
                .andExpect(header().string("Content-Type", containsString("image/png")));
        mockMvc.perform(get("/wiki/{title}", "特殊:文件列表"))
                .andExpect(content().string(containsString(name)));

        // 收紧：该文件仅管理员可看
        accessControlService.saveRule("文件:" + name, AccessAction.READ, PermissionService.GROUP_ADMIN);
        mockMvc.perform(get("/files/{stored}", file.getStoredName()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/wiki/{title}", "特殊:文件列表"))
                .andExpect(content().string(not(containsString(name))));
        // 文件页（文件:名称）同样 403
        mockMvc.perform(get("/wiki/{title}", "文件:" + name))
                .andExpect(status().isForbidden());

        // 管理员可见
        mockMvc.perform(get("/files/{stored}", file.getStoredName()).session(sessionOf(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/wiki/{title}", "文件:" + name).session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(name)));
    }

    @Test
    void specialFileRedirectsToFileNamespacePage() throws Exception {
        WikiUser user = insertUser("跳转用户" + System.nanoTime());
        String name = "跳转图" + System.nanoTime() + ".png";
        upload(user, name);

        // 旧链接 特殊:文件?name=… → 文件:名称
        mockMvc.perform(get("/wiki/{title}", "特殊:文件").param("name", name))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(WikiUrls.pagePath("文件:" + name)));

        // File: 英文别名 → 文件: 规范名
        mockMvc.perform(get("/wiki/{title}", "File:" + name))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(WikiUrls.pagePath("文件:" + name)));
    }

    @Test
    void filePageShowsPreviewDescriptionAndVersionHistory() throws Exception {
        WikiUser user = insertUser("文件页用户" + System.nanoTime());
        String name = "版本图" + System.nanoTime() + ".png";
        upload(user, name, PNG, "第一版说明");
        WikiFile first = fileService.findByName(name).orElseThrow();
        String firstStored = first.getStoredName();

        // 同名重复上传 → 新版本，旧版本归档
        upload(user, name, PNG_V2, "第二版说明");
        WikiFile second = fileService.findByName(name).orElseThrow();
        assertEquals(2, second.getVersion());
        assertNotEquals(firstStored, second.getStoredName());
        assertEquals(1, fileService.versions(second).size());

        // 文件页：预览当前版本 + 说明页正文 + 文件历史（旧版本可查看 / 回滚）
        mockMvc.perform(get("/wiki/{title}", "文件:" + name).session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("src=\"/files/" + second.getStoredName() + "\"")))
                .andExpect(content().string(containsString("第一版说明")))
                .andExpect(content().string(containsString("文件历史")))
                .andExpect(content().string(containsString("/files/" + firstStored)))
                .andExpect(content().string(containsString("恢复此版本")));

        // 旧版本仍可直接访问
        mockMvc.perform(get("/files/{stored}", firstStored))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG));

        // 回滚到 v1：当前内容变成第一版字节（版本号 +1，历史保留）
        mockMvc.perform(post("/api/files/revert").session(sessionOf(user))
                        .param("name", name)
                        .param("version", "1"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("已恢复到旧版本"))
                .andExpect(jsonPath("$.data.version").value(3));
        WikiFile reverted = fileService.findByName(name).orElseThrow();
        mockMvc.perform(get("/files/{stored}", reverted.getStoredName()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG));
        assertEquals(2, fileService.versions(reverted).size());
    }

    @Test
    void wikitextEmbedsUploadedImageAndRedLinksMissingFile() throws Exception {
        WikiUser admin = admin("嵌入图管理员" + System.nanoTime());
        String name = "嵌入图" + System.nanoTime() + ".png";
        String missing = "不存在" + System.nanoTime() + ".png";
        upload(admin, name);

        String title = "嵌入测试页" + System.nanoTime();
        wikiPageService.editPage(admin, title,
                "[[文件:" + name + "|thumb|这是说明|100px]]\n\n[[文件:" + missing + "]]");

        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("width=\"100\"")))
                .andExpect(content().string(containsString("wiki-thumb")))
                .andExpect(content().string(containsString("这是说明")))
                .andExpect(content().string(containsString("class=\"new\"")))
                // 红链指向 文件:名称（不再是 特殊:文件?name=）
                .andExpect(content().string(containsString(WikiUrls.pagePath("文件:" + missing))))
                .andExpect(content().string(not(containsString("特殊:文件?name="))));

        // 原样输出（?action=raw）仍是 wikitext，不受影响
        mockMvc.perform(get("/wiki/{title}", title).param("action", "raw"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("[[文件:" + name)));
    }

    @Test
    void deleteFileRequiresDeletePermission() throws Exception {
        WikiUser user = insertUser("删文件用户" + System.nanoTime());
        String name = "待删除" + System.nanoTime() + ".png";
        upload(user, name);
        WikiFile file = fileService.findByName(name).orElseThrow();

        // 普通用户：403（delete 默认管理员）
        mockMvc.perform(post("/api/files/delete").session(sessionOf(user))
                        .param("id", file.getId().toString()))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("您没有权限删除此文件。"));

        // 管理员：200（软删除），随后 404
        WikiUser admin = admin("删文件管理员" + System.nanoTime());
        mockMvc.perform(post("/api/files/delete").session(sessionOf(admin))
                        .param("id", file.getId().toString()))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("文件已删除"));
        mockMvc.perform(get("/files/{stored}", file.getStoredName()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletedFilesGoToArchiveAndCanBeRestoredOrPurged() throws Exception {
        WikiUser admin = admin("回收站管理员" + System.nanoTime());
        WikiUser user = insertUser("回收站用户" + System.nanoTime());
        String name = "回收站图" + System.nanoTime() + ".png";
        upload(user, name);
        WikiFile file = fileService.findByName(name).orElseThrow();

        // 软删除：文件访问 404、列表隐藏、文件页提示已删除
        mockMvc.perform(post("/api/files/delete").session(sessionOf(admin))
                        .param("id", file.getId().toString()))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/files/{stored}", file.getStoredName()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/wiki/{title}", "文件:" + name))
                .andExpect(content().string(containsString("此文件已被删除")));
        mockMvc.perform(get("/wiki/{title}", "特殊:文件列表"))
                .andExpect(content().string(not(containsString(name))));

        // 回收站：普通用户看不到内容
        mockMvc.perform(get("/wiki/{title}", "特殊:已删除文件").session(sessionOf(user)))
                .andExpect(content().string(containsString("您没有权限查看已删除的文件。")));

        // 管理员可见并恢复
        mockMvc.perform(get("/wiki/{title}", "特殊:已删除文件").session(sessionOf(admin)))
                .andExpect(content().string(containsString(name)));
        mockMvc.perform(post("/api/files/restore").session(sessionOf(admin))
                        .param("id", file.getId().toString()))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("文件已恢复"));
        mockMvc.perform(get("/files/{stored}", file.getStoredName()))
                .andExpect(status().isOk());

        // 彻底删除：元信息与磁盘内容一起清掉（不可恢复）
        mockMvc.perform(post("/api/files/delete").session(sessionOf(admin))
                        .param("id", file.getId().toString())
                        .param("hard", "true"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("文件已彻底删除"));
        assertTrue(fileService.findAnyByName(name).isEmpty());
        mockMvc.perform(get("/files/{stored}", file.getStoredName()))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadPageShowsFormAndRequiresLoginHint() throws Exception {
        mockMvc.perform(get("/wiki/{title}", "特殊:上传文件"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("请先登录后再上传文件")));

        WikiUser user = insertUser("上传页用户" + System.nanoTime());
        mockMvc.perform(get("/wiki/{title}", "特殊:上传文件").session(sessionOf(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"file-upload-form\"")))
                .andExpect(content().string(containsString("id=\"file-input\"")))
                .andExpect(content().string(containsString("最近上传")));
    }

    @Test
    void hardDeleteRequiresPurgePermission() throws Exception {
        WikiUser user = insertUser("永久删除用户" + System.nanoTime());
        String name = "待彻底删除" + System.nanoTime() + ".png";
        upload(user, name);
        WikiFile file = fileService.findByName(name).orElseThrow();

        // 只放开 delete（文件:* → 用户组）：软删除可以，彻底删除仍需要 purge（默认管理员）
        accessControlService.saveRule("文件:*", AccessAction.DELETE, PermissionService.GROUP_USER);
        try {
            mockMvc.perform(post("/api/files/delete").session(sessionOf(user))
                            .param("id", file.getId().toString()).param("hard", "true"))
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.message").value("您没有权限永久删除此文件。"));
            mockMvc.perform(post("/api/files/delete").session(sessionOf(user))
                            .param("id", file.getId().toString()))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("文件已删除"));
        } finally {
            accessControlService.findRule(AccessAction.DELETE, "文件:*")
                    .ifPresent(rule -> accessControlService.deleteRule(rule.getId()));
        }

        // 管理员（purge 默认管理员）可以彻底删除
        WikiUser admin = admin("彻底删除管理员" + System.nanoTime());
        mockMvc.perform(post("/api/files/delete").session(sessionOf(admin))
                        .param("id", file.getId().toString()).param("hard", "true"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("文件已彻底删除"));
        assertTrue(fileService.findAnyByName(name).isEmpty());
    }

    @Test
    void filePageListsPagesUsingIt() throws Exception {
        WikiUser user = insertUser("文件用途用户" + System.nanoTime());
        String name = "用途图" + System.nanoTime() + ".png";
        upload(user, name);
        String page = "引用该图的页面" + System.nanoTime();
        wikiPageService.editPage(user, page, "说明\n\n[[文件:" + name + "|thumb|100px]]");

        // 文件页显示「文件用途」并链接到使用它的页面
        mockMvc.perform(get("/wiki/{title}", "文件:" + name))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("文件用途")))
                .andExpect(content().string(containsString(page)))
                .andExpect(content().string(containsString(WikiUrls.pagePath(page))));

        // 模板带入的图片也算（如信息框模板）
        String template = "图片框" + System.nanoTime();
        wikiPageService.editPage(user, "模板:" + template, "[[文件:{{{1|}}}]]");
        String templatePage = "模板带入图页面" + System.nanoTime();
        wikiPageService.editPage(user, templatePage, "{{" + template + "|" + name + "}}");
        mockMvc.perform(get("/wiki/{title}", "文件:" + name))
                .andExpect(content().string(containsString(templatePage)));

        // 没有被任何页面使用的文件：给出提示
        String lonely = "无人用图" + System.nanoTime() + ".png";
        upload(user, lonely);
        mockMvc.perform(get("/wiki/{title}", "文件:" + lonely))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("本文件未被任何页面使用")));
    }

    // ==================== 工具 ====================

    private void upload(WikiUser user, String name) throws Exception {
        upload(user, name, PNG, "测试说明");
    }

    private void upload(WikiUser user, String name, byte[] bytes, String description) throws Exception {
        mockMvc.perform(multipart("/api/files/upload").session(sessionOf(user))
                        .file(new MockMultipartFile("file", name, "image/png", bytes))
                        .param("description", description))
                .andExpect(jsonPath("$.code").value(200));
    }

    private WikiUser admin(String username) {
        WikiUser admin = insertUser(username);
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
