package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多文件批量上传已迁到插件 {@code multi-upload}（仿 MsUpload）：
 * <ul>
 *     <li>core 的上传页始终是**单文件表单**（「目标文件名」输入框 + 文件框不多选）；</li>
 *     <li>批量编排（多选列表、逐个上传、插入画廊、编辑器对话框批量插入）由插件脚本接管，
 *     插件未加载时上传页与迁移前的「开关关闭」状态一致；</li>
 *     <li>站点设置里的开关（{@code features.multi-upload}）仍由 core 维护，插件经 {@code SiteConfig} 读同一个键；</li>
 *     <li>服务端的批量语义不变：客户端逐个 {@code POST /api/files/upload}，每个文件独立成文件页。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class MultiUploadTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private FeatureService featureService;
    @Autowired
    private SiteSettingsService siteSettingsService;


    @Autowired
    private PermissionService permissionService;

    @AfterEach
    void resetFlag() {
        siteSettingsService.set("features.multi-upload", Boolean.toString(false));
    }

    @Test
    void uploadPageIsAlwaysSingleFileInCore() throws Exception {
        WikiUser user = insertUser("多文件上传用户" + System.nanoTime());
        MockHttpSession session = sessionOf(user);

        // 无论开关如何：core 都是单文件表单，没有多选列表 / 多选属性
        for (boolean enabled : List.of(false, true)) {
            siteSettingsService.set("features.multi-upload", Boolean.toString(enabled));
            mockMvc.perform(get("/wiki/{title}", "特殊:上传文件").session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("id=\"file-input\"")))
                    .andExpect(content().string(not(containsString("multiple=\""))))
                    .andExpect(content().string(containsString("id=\"file-name\"")))
                    .andExpect(content().string(not(containsString("id=\"file-multi-list\""))))
                    .andExpect(content().string(not(containsString("/plugins/multi-upload/"))));
        }
    }

    @Test
    void editorPageHasNoMultiUploadConfigWithoutPlugin() throws Exception {
        WikiUser user = insertUser("多文件编辑用户" + System.nanoTime());
        String page = "多文件编辑页" + System.nanoTime();
        wikiPageService.editPage(user, page, "内容");

        siteSettingsService.set("features.multi-upload", Boolean.toString(true));
        mockMvc.perform(get("/wiki/{title}", page).param("action", "edit").session(sessionOf(user)))
                .andExpect(status().isOk())
                // 多文件开关的 WIKI_CONFIG 与脚本都由插件注入，core 不再输出
                .andExpect(content().string(not(containsString("multiUpload:"))))
                .andExpect(content().string(not(containsString("/plugins/multi-upload/"))));
    }

    @Test
    void batchUploadsCreateSeparateFiles() throws Exception {
        // 客户端的多文件上传 = 逐个 POST /api/files/upload；服务端每个文件独立成文件页
        WikiUser user = insertUser("批量上传用户" + System.nanoTime());
        String prefix = "批量图" + System.nanoTime();

        for (int index = 0; index < 3; index++) {
            mockMvc.perform(multipart("/api/files/upload").session(sessionOf(user))
                            .file(new MockMultipartFile("file", prefix + "第" + index + "张.png",
                                    "image/png", PNG))
                            .param("description", "批量上传说明"))
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.name").value(prefix + "第" + index + "张.png"));
        }

        mockMvc.perform(get("/wiki/{title}", "文件:" + prefix + "第0张.png"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("批量上传说明")));
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
