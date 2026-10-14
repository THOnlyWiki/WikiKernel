package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.InvalidTitleException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 标题规范化与校验（仿 MediaWiki）：
 * 下划线等价空格、连续空白折叠、首字母自动大写、非法字符拒绝；
 * 页面查询 / 保存 / 原始内容 / 文件页 / 用户页 / Action API 都要一致。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class WikiTitleNormalizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private FileService fileService;

    @Autowired
    private UserService userService;

    @Test
    void underscoreResolvesToSpacedPage() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String canonical = "下划线标题" + suffix + " 页面";
        String underscored = "下划线标题" + suffix + "_页面";
        wikiPageService.editPage(null, canonical, "内容ABC");

        assertTrue(wikiPageService.getByTitle(underscored).isPresent(), "下划线标题应能查到空格标题");
        assertTrue(wikiPageService.getVisibleByTitle(underscored).isPresent());

        mockMvc.perform(get("/wiki/{title}", underscored))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("内容ABC")));
        mockMvc.perform(get("/wiki/{title}", underscored).param("action", "history"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/wiki/{title}", underscored).param("action", "raw"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("内容ABC")));

        // 用下划线标题再次保存：写回同一个规范标题，不应产生重复页面
        wikiPageService.editPage(null, underscored, "内容DEF");
        assertEquals(1L, wikiPageMapper.selectCount(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, canonical)), "不应产生「下划线版」重复页面");
        String latest = wikiPageService.getLatestContent(
                wikiPageService.getByTitle(canonical).orElseThrow().getId()).orElseThrow();
        assertEquals("内容DEF", latest);
    }

    @Test
    void consecutiveUnderscoresAndSpacesCollapse() {
        String suffix = String.valueOf(System.nanoTime());
        wikiPageService.editPage(null, "多_空格__标题" + suffix, "折叠测试");
        assertTrue(wikiPageService.getByTitle("多 空格 标题" + suffix).isPresent(),
                "连续下划线/空格应折叠成一个空格");
        assertTrue(wikiPageService.getByTitle("多_空格 标题" + suffix).isPresent(),
                "混合写法也应命中同一标题");
    }

    @Test
    void filePageUnderscoreEqualsSpace() throws Exception {
        String name = "My File" + System.nanoTime() + ".png";
        fileService.store(null, name, "测试文件", new byte[]{1, 2, 3});
        String underscoredTitle = "文件:" + name.replace(' ', '_');

        assertTrue(fileService.findByName(name.replace(' ', '_')).isPresent(),
                "文件名查询应支持下划线/空格互换");
        mockMvc.perform(get("/wiki/{title}", underscoredTitle))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(name)));
    }

    @Test
    void actionApiTitlesNormalizeUnderscores() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String canonical = "API下划线" + suffix + " 页";
        wikiPageService.editPage(null, canonical, "api 内容");
        mockMvc.perform(get("/api.php")
                        .param("action", "query")
                        .param("titles", canonical.replace(' ', '_'))
                        .param("formatversion", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.pages[0].title").value(canonical));
    }

    @Test
    void plusInTitleIsLiteralInPath() throws Exception {
        // MediaWiki 语义：路径里的 '+' 是字面加号（不是空格）；'=' 也允许出现在标题里
        String title = "1+1=2";
        wikiPageService.editPage(null, title, "加号测试内容");

        mockMvc.perform(get("/wiki/1+1=2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("加号测试内容")));
        mockMvc.perform(get(java.net.URI.create("/wiki/1%2B1%3D2")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("加号测试内容")));
        // 空格形式是另一个标题（不存在）→ 404
        mockMvc.perform(get(java.net.URI.create("/wiki/1%201=2")))
                .andExpect(status().isNotFound());
        // 下划线版本也进不去（下划线只等价空格，不等价加号）
        mockMvc.perform(get(java.net.URI.create("/wiki/1_1=2")))
                .andExpect(status().isNotFound());
    }

    @Test
    void notFoundPageShowsDecodedTitleWithLiteralPlus() throws Exception {
        // 404 错误页显示被访问的标题：'+' 保持字面量，%20 解码为空格，下划线归一为空格
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/wiki/1+1=2"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("1+1=2")));

        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/wiki/A%20B%2BC"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("A B+C")));

        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/wiki/Java_%E5%BC%80%E5%8F%91%E6%95%99%E7%A8%8B"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Java 开发教程")));
    }

    @Test
    void firstLetterCapitalizedForReadWriteAndApi() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String lowercase = "case 测试页" + suffix;
        String canonical = "Case 测试页" + suffix;
        wikiPageService.editPage(null, lowercase, "大小写内容");

        assertEquals(1L, wikiPageMapper.selectCount(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, canonical)), "应存成首字母大写的规范标题");
        assertTrue(wikiPageService.getByTitle(lowercase).isPresent(), "小写标题应查到同一页");

        mockMvc.perform(get("/wiki/{title}", lowercase))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("大小写内容")));
        mockMvc.perform(get("/api.php").param("action", "query")
                        .param("titles", lowercase).param("formatversion", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query.pages[0].title").value(canonical));

        // 用小写标题再次保存：写回同一页，不产生「小写版」重复页面
        wikiPageService.editPage(null, lowercase, "第二版");
        assertEquals(1L, wikiPageMapper.selectCount(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getTitle, canonical)), "小写标题保存不应产生重复页");
    }

    @Test
    void illegalCharactersRejectedOnSaveAndShownOnForms() throws Exception {
        String bad = "非法[标题" + System.nanoTime();
        assertThrows(InvalidTitleException.class, () -> wikiPageService.editPage(null, bad, "x"));
        assertTrue(wikiPageService.getByTitle(bad).isEmpty(), "非法标题不应被写入");

        // 直接 POST：400（标题在保存前就被拒绝，页面不创建）
        String encoded = URLEncoder.encode(bad, StandardCharsets.UTF_8);
        mockMvc.perform(post(java.net.URI.create("/wiki/" + encoded)).with(csrf())
                        .param("content", "x"))
                .andExpect(status().isBadRequest());
        assertTrue(wikiPageService.getByTitle(bad).isEmpty(), "非法标题不应被写入");

        // 特殊:创建页面：留在表单上提示，不跳转到编辑页
        mockMvc.perform(get("/wiki/{title}", "特殊:创建页面").param("pagename", bad))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("非法字符")));
    }

    @Test
    void lowercaseUsernameUserPageResolvesAfterCapitalization() throws Exception {
        String name = "caseuser" + System.nanoTime();
        WikiUser user = userService.createUser(name, "secret123", "");
        wikiPageService.editPage(null, "用户:" + name, "用户页内容");

        mockMvc.perform(get("/wiki/{title}", "用户:" + name))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("用户页内容")));
        String capitalized = "用户:" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        mockMvc.perform(get("/wiki/{title}", capitalized))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("用户页内容")));
    }

    @Test
    void fileNameCapitalizedAndIllegalRejected() {
        String suffix = String.valueOf(System.nanoTime());
        String lower = "first" + suffix + ".png";
        String upper = "First" + suffix + ".png";
        fileService.store(null, lower, "说明", new byte[]{1, 2, 3});

        assertTrue(fileService.findByName(upper).isPresent(), "文件应存成首字母大写");
        assertTrue(fileService.findByName(lower).isPresent(), "小写文件名应命中同一文件");
        assertThrows(IllegalArgumentException.class,
                () -> fileService.store(null, "bad[名" + suffix + ".png", "", new byte[]{1}));
    }
}
