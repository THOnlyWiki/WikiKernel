package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 页面预览（Popups / Page Previews）：已迁到插件 {@code popups}（脚本与样式由插件注入）；
 * core 保留通用预览 API {@code GET /api/page-preview}（首段摘要与缩略图；重定向跟随一次；不存在 / 特殊页 404）
 * 与 {@code WIKI_CONFIG.popups} 开关。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.run-path=./build/test-files"
})
@AutoConfigureMockMvc
class PopupsTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private FileService fileService;

    @Autowired
    private FeatureService featureService;
    @Autowired
    private SiteSettingsService siteSettingsService;


    @Test
    void previewReturnsPlainExtractAndThumbnail() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String file = "预览图" + suffix + ".png";
        fileService.store(null, file, "", PNG);
        String title = "预览测试页" + suffix;
        wikiPageService.editPage(null, title,
                "'''粗体摘要'''，还有[[首页|链接文字]]。\n[[文件:" + file + "|thumb|说明]]\n\n== 第二节 ==\n第二节的内容不该出现在摘要里。");

        mockMvc.perform(get("/api/page-preview").param("title", title))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value(title))
                .andExpect(jsonPath("$.data.url").value(containsString("/wiki/")))
                .andExpect(jsonPath("$.data.url").value(containsString(
                        java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8))))
                // 摘要为纯文本：去掉 ''' 与 [[…|…]] 标记，只保留显示文字
                .andExpect(jsonPath("$.data.extract").value(containsString("粗体摘要")))
                .andExpect(jsonPath("$.data.extract").value(containsString("链接文字")))
                .andExpect(jsonPath("$.data.extract").value(not(containsString("'''"))))
                .andExpect(jsonPath("$.data.extract").value(not(containsString("[["))))
                // 只取首段（第二节不进摘要）
                .andExpect(jsonPath("$.data.extract").value(not(containsString("第二节的内容"))))
                // 首段里的第一张图片作为缩略图
                .andExpect(jsonPath("$.data.thumbnail").value(containsString("/files/")))
                .andExpect(jsonPath("$.data.thumbnailAlt").value(file));
    }

    @Test
    void previewTruncatesLongText() throws Exception {
        String title = "预览长文页" + System.nanoTime();
        String lead = "这是很长的首段内容。".repeat(60);
        wikiPageService.editPage(null, title, lead);
        mockMvc.perform(get("/api/page-preview").param("title", title))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.extract").value(org.hamcrest.Matchers.endsWith("…")))
                .andExpect(jsonPath("$.data.extract").value(org.hamcrest.Matchers.hasLength(
                        org.hamcrest.Matchers.lessThan(360))));
    }

    @Test
    void previewFollowsRedirectOnce() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String target = "预览目标页" + suffix;
        String redirect = "预览重定向页" + suffix;
        wikiPageService.editPage(null, target, "目标页的首段内容。");
        wikiPageService.editPage(null, redirect, "#REDIRECT [[" + target + "]]");
        mockMvc.perform(get("/api/page-preview").param("title", redirect))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value(redirect))
                .andExpect(jsonPath("$.data.extract").value(containsString("目标页的首段内容")));
    }

    @Test
    void previewUnavailableForMissingAndSpecialPages() throws Exception {
        mockMvc.perform(get("/api/page-preview").param("title", "绝对不存在的预览页" + System.nanoTime()))
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/api/page-preview").param("title", "特殊:版本"))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void popupsConfigFlagIsInjectedByThePluginNotCore() throws Exception {
        // Popups 前端已迁到插件 popups：脚本、样式与 window.WIKI_CONFIG.popups 都由插件的
        // HtmlHeadProvider 注入（core 不再内置这个功能开关）
        String title = "预览开关测试页" + System.nanoTime();
        wikiPageService.editPage(null, title, "内容");
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("popups:"))))
                .andExpect(content().string(not(containsString("/js/popups.js"))));
    }
}
