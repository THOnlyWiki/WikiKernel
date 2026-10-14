package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多媒体预览（仿 MediaWiki TimedMediaHandler）：
 * <ul>
 *     <li>功能开关 {@code features.media-viewer}（默认关闭，站点设置可开）；</li>
 *     <li>开启后词条里的 {@code [[文件:X.webm|thumb|300px|说明]]} 渲染播放器（带宽度与图注），关闭时是文件占位框；</li>
 *     <li>音视频文件页显示播放器；</li>
 *     <li>音视频通过 {@code /files/**} 内联播放并支持 HTTP Range（拖动进度条）；</li>
 *     <li>扩展名 → MIME 按服务端映射（不信任客户端）。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class MediaViewerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureService featureService;
    @Autowired
    private SiteSettingsService siteSettingsService;


    @Autowired
    private FileService fileService;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    @Test
    void videoEmbedRendersPlaceholderWithoutPlugin() throws Exception {
        WikiUser admin = adminUser();
        String name = "测试视频" + System.nanoTime() + ".webm";
        fileService.store(admin, name, "测试", new byte[]{1, 2, 3, 4});
        String page = "媒体嵌入页" + System.nanoTime();
        wikiPageService.editPage(admin, page, "[[文件:" + name + "|thumb|300px|说明文字]]");

        // 播放器已迁到插件 media-viewer：core 未加载插件时始终显示文件占位框（开关开着也一样）
        siteSettingsService.set("features.media-viewer", Boolean.toString(false));
        String off = renderPage(page);
        assertFalse(off.contains("wiki-media-player"), "关闭时不应出现播放器");
        assertTrue(off.contains("wiki-file-embed"), "关闭时显示文件占位框");

        siteSettingsService.set("features.media-viewer", Boolean.toString(true));
        try {
            String on = renderPage(page);
            assertFalse(on.contains("wiki-media-player"), "插件未加载时也不应出现播放器");
            assertTrue(on.contains("wiki-file-embed"), "插件未加载时显示文件占位框");
            assertTrue(on.contains("说明文字"), "thumb 图注应保留");
        } finally {
            siteSettingsService.set("features.media-viewer", Boolean.toString(false));
        }
    }

    @Test
    void filePageShowsPlaceholderWithoutPlugin() throws Exception {
        WikiUser admin = adminUser();
        String name = "测试音频" + System.nanoTime() + ".mp3";
        fileService.store(admin, name, "测试", new byte[]{5, 6, 7, 8});

        siteSettingsService.set("features.media-viewer", Boolean.toString(true));
        try {
            String html = mockMvc.perform(get("/wiki/{title}", "文件:" + name))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            // 文件页的播放器由插件 media-viewer 提供（FileEmbedRenderer 扩展点）
            assertFalse(html.contains("wiki-media-player"), "插件未加载时文件页没有播放器");
            assertTrue(html.contains(name), "仍然显示文件名");
        } finally {
            siteSettingsService.set("features.media-viewer", Boolean.toString(false));
        }
    }

    @Test
    void mediaFilesServeInlineWithRangeSupport() throws Exception {
        WikiUser admin = adminUser();
        String name = "测试范围" + System.nanoTime() + ".mp4";
        byte[] bytes = new byte[100];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        WikiFile file = fileService.store(admin, name, "范围测试", bytes);
        assertEquals("video/mp4", file.getContentType(), "服务端映射 MIME");
        assertEquals("video", fileService.mediaKind(name));

        String url = "/files/" + file.getStoredName();
        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(content().bytes(bytes));

        mockMvc.perform(get(url).header("Range", "bytes=10-19"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 10-19/100"))
                .andExpect(content().bytes(Arrays.copyOfRange(bytes, 10, 20)));
    }

    @Test
    void imageMarkupCarriesViewerAttributes() throws Exception {
        WikiUser admin = adminUser();
        String name = "查看器图片" + System.nanoTime() + ".png";
        fileService.store(admin, name, "图", new byte[]{9, 8, 7, 6});
        String page = "图片查看页" + System.nanoTime();
        wikiPageService.editPage(admin, page, "[[文件:" + name + "|thumb|300px|图注文字]]");

        String embed = renderPage(page);
        assertTrue(embed.contains("class=\"wiki-media-image\""), "嵌入图片应带查看器标记");
        assertTrue(embed.contains("data-file=\"文件:" + name + "\""), "嵌入图片应带文件标题");
        assertTrue(embed.contains("data-caption=\"图注文字\""), "嵌入图片应带图注");
        // 图片放大查看器脚本由插件 media-viewer 注入（core 未加载插件时不出现）
        assertFalse(embed.contains("image-viewer.js"), "插件未加载时不应加载图片查看器脚本");

        String filePage = mockMvc.perform(get("/wiki/{title}", "文件:" + name))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(filePage.contains("class=\"wiki-media-image\""), "文件页大图应带查看器标记");
        assertTrue(filePage.contains("data-file=\"文件:" + name + "\""), "文件页大图应带文件标题");
    }

    // ==================== 工具 ====================

    private String renderPage(String title) throws Exception {
        return mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }

    private WikiUser adminUser() {
        WikiUser user = insertUser("媒体测试管理员" + System.nanoTime());
        permissionService.replaceFor(user.getId(), List.of(PermissionService.GROUP_ADMIN));
        return user;
    }

    private MockHttpSession sessionOf(WikiUser user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        return session;
    }
}
