package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户名一律渲染成指向用户页（{@code 用户:XXX}）的蓝链：覆盖页面历史、特殊:日志、特殊:用户贡献、
 * 特殊:封禁列表、文件页历史与特殊:已删除文件、特殊:大量删除、特殊:审核。
 * <p>
 * 匿名编辑 / 找不到用户名时不加链接（不能链到「用户:匿名用户」这种不存在的标题）。
 * <p>
 * 滥用过滤器 / 滥用日志的同类断言随功能迁到插件（{@code plugin/abuse-filter} 的用例覆盖）。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.run-path=./build/test-files"
})
@AutoConfigureMockMvc
class UserLinkTest {

    /** 1×1 透明 PNG。 */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private BlockService blockService;

    @Autowired
    private FileService fileService;

    @Autowired
    private FeatureService featureService;
    @Autowired
    private SiteSettingsService siteSettingsService;


    @Test
    void pageHistoryLinksTheRevisionAuthor() throws Exception {
        WikiUser actor = insertUser("历史作者" + System.nanoTime());
        String title = "用户链接历史页" + System.nanoTime();
        wikiPageService.editPage(actor, title, "内容");

        mockMvc.perform(get("/wiki/{title}", title).param("action", "history"))
                .andExpect(status().isOk())
                .andExpect(content().string(userLink(actor.getUsername())));
    }

    @Test
    void logAndContributionsLinkTheActor() throws Exception {
        WikiUser actor = insertUser("日志链接用户" + System.nanoTime());
        String title = "用户链接日志页" + System.nanoTime();
        wikiPageService.editPage(actor, title, "内容");

        // 特殊:日志：操作者列
        mockMvc.perform(get("/wiki/{title}", "特殊:日志").param("name", actor.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("创建了页面")))
                .andExpect(content().string(userLink(actor.getUsername())));

        // 特殊:用户贡献：导语里的用户名
        mockMvc.perform(get("/wiki/{title}", "特殊:用户贡献").param("name", actor.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(userLink(actor.getUsername())));
    }

    @Test
    void blockListLinksTheBlocker() throws Exception {
        WikiUser admin = insertUser("封禁操作管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        String target = "封禁目标用户" + System.nanoTime();
        insertUser(target);
        blockService.block(admin, target, LocalDateTime.now().plusHours(1), "用户链接测试", false, false, false);

        mockMvc.perform(get("/wiki/{title}", "特殊:封禁列表").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(userLink(admin.getUsername())));
    }

    @Test
    void fileHistoryAndDeletedFilesLinkUploaderAndDeleter() throws Exception {
        WikiUser uploader = insertUser("上传者" + System.nanoTime());
        WikiUser admin = insertUser("文件管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        String name = "用户链接文件" + System.nanoTime() + ".png";

        mockMvc.perform(multipart("/api/files/upload").session(sessionOf(uploader))
                        .file(new MockMultipartFile("file", name, "image/png", PNG)))
                .andExpect(status().isOk());

        // 文件页：头部上传者 + 历史表格里的上传者
        mockMvc.perform(get("/wiki/{title}", "文件:" + name).session(sessionOf(uploader)))
                .andExpect(status().isOk())
                .andExpect(content().string(userLink(uploader.getUsername())));

        // 删除后：特殊:已删除文件 里上传者与删除者都是蓝链
        var file = fileService.findByName(name).orElseThrow();
        mockMvc.perform(post("/api/files/delete").session(sessionOf(admin))
                        .param("id", String.valueOf(file.getId())).param("reason", "用户链接测试"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/wiki/{title}", "特殊:已删除文件").session(sessionOf(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(userLink(uploader.getUsername())))
                .andExpect(content().string(userLink(admin.getUsername())));
    }

    @Test
    void nukeLinksTheLastEditor() throws Exception {
        WikiUser admin = insertUser("大量删除管理员" + System.nanoTime());
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        WikiUser editor = insertUser("大量删除编辑者" + System.nanoTime());
        wikiPageService.editPage(editor, "用户链接大量删除页" + System.nanoTime(), "内容");

        mockMvc.perform(get("/wiki/{title}", "特殊:大量删除")
                        .session(sessionOf(admin))
                        .param("name", editor.getUsername()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("最后编辑者")))
                .andExpect(content().string(userLink(editor.getUsername())));
    }

    @Test
    void moderationQueueIsProvidedByThePluginOnly() throws Exception {
        // 审核（含队列页里「提交人 → 用户页」的蓝链）已迁到插件 moderation：
        // core 未加载插件时 特殊:审核 不存在，编辑立即生效（插件侧的蓝链断言见 ModerationPluginTest）
        siteSettingsService.set("features.moderation", Boolean.toString(true));
        try {
            WikiUser author = insertUser("审核提交者" + System.nanoTime());
            String title = "用户链接审核页" + System.nanoTime();
            wikiPageService.editPage(null, title, "旧内容");
            mockMvc.perform(post("/wiki/{title}", title).with(csrf())
                            .session(sessionOf(author)).param("content", "新内容"))
                    .andExpect(status().is3xxRedirection());
            assertEquals("新内容", wikiPageService.getLatestContent(
                    wikiPageService.getByTitle(title).orElseThrow().getId()).orElseThrow());

            mockMvc.perform(get("/wiki/{title}", "特殊:审核").session(sessionOf(author)))
                    .andExpect(status().isNotFound());
        } finally {
            siteSettingsService.set("features.moderation", Boolean.toString(false));
        }
    }

    // ==================== 工具 ====================

    /**
     * 用户页链接的断言：路径里的冒号可能被编码成 {@code %3A}（{@code WikiUrls.pagePath} 生成的动态 href）
     * 或保留字面冒号（Thymeleaf {@code @{/wiki/{t}(…)} } 生成的 href）——两种都是合法链接，都算通过。
     */
    private Matcher<String> userLink(String username) {
        String encoded = URLEncoder.encode("用户:" + username, StandardCharsets.UTF_8).replace("+", "%20");
        return anyOf(containsString(encoded), containsString(encoded.replace("%3A", ":")));
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
