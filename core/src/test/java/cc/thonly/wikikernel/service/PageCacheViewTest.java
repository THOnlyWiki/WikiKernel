package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页面查看（{@code GET /wiki/{title}}）的缓存接入：
 * 第一次渲染后正文进缓存；命中时直接用缓存里的 HTML（不再渲染）；
 * 页面一改（新版本）立刻回到新正文，不会拿到旧内容。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class PageCacheViewTest {

    /** 原始正文里的独有标记。 */
    private static final String ORIGINAL_MARK = "接入点原始正文ABC";

    /** 直接塞进缓存的独有标记。 */
    private static final String CACHED_MARK = "接入点缓存正文XYZ";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PageCacheService pageCacheService;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private SiteSettingsService siteSettingsService;

    @AfterEach
    void restoreDefaults() {
        siteSettingsService.set(PageCacheService.ENABLED_KEY, "false");
        siteSettingsService.set(PageCacheService.BACKEND_KEY, "auto");
    }

    @Test
    void pageViewReadsAndWritesThroughTheCache() throws Exception {
        siteSettingsService.set(PageCacheService.ENABLED_KEY, "true");
        siteSettingsService.set(PageCacheService.BACKEND_KEY, "memory");
        String title = "缓存接入页" + System.nanoTime();
        WikiPage page = wikiPageService.editPage(null, title, "'''" + ORIGINAL_MARK + "'''");

        // 第一次查看：未命中 → 渲染 → 写缓存
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ORIGINAL_MARK)));
        assertTrue(pageCacheService.get(title, page.getLatestRevisionId()).isPresent(),
                "页面查看之后正文应该进缓存");

        // 把缓存里的 HTML 换成标记：再查看时页面必须直接用缓存（证明读的就是这条路径）
        pageCacheService.put(title, "<p id=\"cache-marker\">" + CACHED_MARK + "</p>",
                page.getLatestRevisionId());
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(CACHED_MARK)))
                .andExpect(content().string(not(containsString(ORIGINAL_MARK))));

        // 页面被编辑（新版本）→ 立刻拿到新正文，不会返回缓存里的旧内容
        wikiPageService.editPage(null, title, "'''第三版正文DEF'''");
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("第三版正文DEF")))
                .andExpect(content().string(not(containsString(CACHED_MARK))));
    }

    @Test
    void oldRevisionViewIsRenderedWithoutCache() throws Exception {
        siteSettingsService.set(PageCacheService.ENABLED_KEY, "true");
        siteSettingsService.set(PageCacheService.BACKEND_KEY, "memory");
        String title = "缓存旧版本页" + System.nanoTime();
        WikiPage first = wikiPageService.editPage(null, title, "'''旧版本内容GHI'''");
        Long firstRevision = first.getLatestRevisionId();
        wikiPageService.editPage(null, title, "'''新版本内容JKL'''");

        // 把最新版本的缓存换成标记，再按 ?oldid= 看旧版本：旧版本必须实时渲染（不读缓存）
        pageCacheService.put(title, "<p>" + CACHED_MARK + "</p>",
                wikiPageService.getByTitle(title).orElseThrow().getLatestRevisionId());
        mockMvc.perform(get("/wiki/{title}", title).param("oldid", String.valueOf(firstRevision)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("旧版本内容GHI")))
                .andExpect(content().string(not(containsString(CACHED_MARK))));
    }
}
