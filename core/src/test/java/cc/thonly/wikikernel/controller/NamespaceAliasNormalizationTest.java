package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.WikiNamespaces;
import cc.thonly.wikikernel.web.WikiTitles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 命名空间英文别名归一（core 级回归）：
 * {@code 别名:标题} 与 {@code 规范名:标题} 必须是<b>同一个页面</b>（MediaWiki 语义），
 * 由 {@link WikiNamespaces#canonicalize(String)} 在 {@link WikiTitles#normalize(String)} 里统一完成。
 * <ul>
 *     <li>遍历别名表：每个命名空间的每个别名前缀都要命中规范名那一行（双向）；</li>
 *     <li>大小写变体（{@code user:} / {@code USER:} / {@code tEmPlAtE:}）命中；</li>
 *     <li>{@code Special:} / {@code 特殊:} 都能打开特殊页；</li>
 *     <li>最长前缀优先（{@code 用户讨论:X} 不能被 {@code 用户:} 吃掉）；</li>
 *     <li>无命名空间标题与历史别名页（修复前落库的 {@code Gadget:X}）行为不变。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class NamespaceAliasNormalizationTest {

    /** 建页时写入的正文标记：别名 URL 必须能读到它。 */
    private static final String MARKER = "命名空间别名归一测试正文";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Test
    void everyAliasPrefixHitsTheSamePageAsItsCanonicalPrefix() throws Exception {
        for (WikiNamespaces.Namespace namespace : WikiNamespaces.all()) {
            if (WikiNamespaces.MAIN.equals(namespace)) {
                continue;
            }
            String canonicalPrefix = namespace.prefixes().get(0);
            String name = uniqueName();
            ensureAccount(namespace, name);
            String canonicalTitle = canonicalPrefix + name;

            // 建页用规范名：每个别名前缀（含大小写变体）都要命中同一行、并能读到正文
            wikiPageService.editPage(null, canonicalTitle, MARKER);
            assertTrue(wikiPageService.getByTitle(canonicalTitle).isPresent(), canonicalTitle);
            for (String prefix : namespace.prefixes()) {
                for (String spelling : spellings(prefix)) {
                    String aliasTitle = spelling + name;
                    assertEquals(canonicalTitle, storedTitleOf(aliasTitle),
                            aliasTitle + " 应命中 " + canonicalTitle);
                    assertPageBody(aliasTitle);
                }
            }

            // 反向：用别名建页 → 规范名读到同一行，且落库标题就是规范名
            String reverseName = uniqueName();
            ensureAccount(namespace, reverseName);
            String canonicalReverse = canonicalPrefix + reverseName;
            String aliasSpelling = namespace.prefixes().get(namespace.prefixes().size() - 1);
            wikiPageService.editPage(null, aliasSpelling + reverseName, MARKER);
            assertEquals(canonicalReverse, storedTitleOf(canonicalReverse),
                    aliasSpelling + reverseName + " 建页后应落库为 " + canonicalReverse);
            assertPageBody(canonicalReverse);
        }
    }

    @Test
    void mixedCaseAliasPrefixesResolveToTheCanonicalTitle() {
        assertEquals("模板:信息框", WikiTitles.normalize("tEmPlAtE:信息框"));
        assertEquals("用户:张三", WikiTitles.normalize("UsEr:张三"));
        assertEquals("用户:张三", WikiTitles.normalize("USER:张三"));
        assertEquals("用户:张三", WikiTitles.normalize("user:张三"));
        assertEquals("用户讨论:张三", WikiTitles.normalize("USER_TALK:张三"), "下划线等价于空格");
        assertEquals("文件:图片.png", WikiTitles.normalize("IMAGE:图片.png"));
        assertEquals("文件:图片.png", WikiTitles.normalize("image:图片.png"));
        assertEquals("特殊:版本", WikiTitles.normalize("SPECIAL:版本"));
        assertEquals("小部件:脚本.js", WikiTitles.normalize("gadget:脚本.js"));
        assertEquals("WikiKernel讨论:入口", WikiTitles.normalize("wikikernel TALK:入口"));
    }

    @Test
    void longestAliasPrefixWins() {
        String name = "前缀优先级" + System.nanoTime();
        assertEquals("用户:" + name, WikiTitles.normalize("User:" + name));
        assertEquals("用户讨论:" + name, WikiTitles.normalize("User talk:" + name));
        assertEquals("用户讨论:" + name, WikiTitles.normalize("用户讨论:" + name));
        assertEquals("分类:" + name, WikiTitles.normalize("Category:" + name));
        assertEquals("分类讨论:" + name, WikiTitles.normalize("Category talk:" + name));
        assertEquals("WikiKernel:" + name, WikiTitles.normalize("wikikernel:" + name));
        assertEquals("WikiKernel讨论:" + name, WikiTitles.normalize("WikiKernel talk:" + name));
    }

    @Test
    void specialPageOpensWithEitherPrefix() throws Exception {
        for (String prefix : List.of("特殊:", "Special:", "special:")) {
            mockMvc.perform(get("/wiki/{title}", prefix + "版本"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("已安装的软件")));
        }
        // 归一后内部标题就是中文规范前缀（特殊页注册表按规范名匹配）
        assertEquals("特殊:版本", WikiTitles.normalize("Special:版本"));
    }

    @Test
    void titlesWithoutNamespacePrefixKeepTheirBehavior() throws Exception {
        assertEquals("Java 开发教程", WikiTitles.normalize("java 开发教程"));
        assertEquals("Time: now", WikiTitles.normalize("time: now"), "非命名空间的冒号属于主命名空间");
        assertEquals("Foo", WikiTitles.normalize(":foo"), "开头冒号表示主命名空间");
        assertEquals("", WikiTitles.normalize("   "));

        String title = "无命名空间页面" + System.nanoTime();
        wikiPageService.editPage(null, title, MARKER);
        assertEquals(title, storedTitleOf(title));
        assertPageBody(title);
    }

    @Test
    void pagesSavedUnderAnAliasPrefixBeforeTheFixStayReachable() throws Exception {
        // 修复前按英文别名落库的历史行（真实库里存在 Gadget: 小工具页）
        String name = "历史别名页" + System.nanoTime() + ".js";
        LocalDateTime now = LocalDateTime.now();
        WikiPage legacy = new WikiPage();
        legacy.setTitle("Gadget:" + name);
        legacy.setHided(false);
        legacy.setDeleted(false);
        legacy.setProtectedPage(false);
        legacy.setCreateAt(now);
        legacy.setUpdateAt(now);
        wikiPageMapper.insert(legacy);

        // 规范名先查、查不到再用别名回退：历史行仍然可读
        assertEquals("Gadget:" + name, storedTitleOf("小部件:" + name));
        assertEquals("Gadget:" + name, storedTitleOf("Gadget:" + name));
        mockMvc.perform(get("/wiki/{title}", "小部件:" + name)).andExpect(status().isOk());
        mockMvc.perform(get("/wiki/{title}", "Gadget:" + name)).andExpect(status().isOk());
    }

    @Test
    void templateTransclusionAndActionApiAcceptAliasPrefixes() throws Exception {
        String name = "别名模板" + System.nanoTime();
        String body = "模板正文" + System.nanoTime();
        wikiPageService.editPage(null, "模板:" + name, body);

        // 转写：{{Template:X}} 与 {{模板:X}} 都要包含同一个模板
        for (String call : List.of("Template:", "模板:")) {
            String page = "别名模板使用者" + System.nanoTime();
            wikiPageService.editPage(null, page, "{{" + call + name + "}}");
            mockMvc.perform(get("/wiki/{title}", page))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(body)));
        }

        // Action API：titles= 参数走同一条归一链路（返回规范标题 + 正文）
        mockMvc.perform(get("/api.php").param("action", "query")
                        .param("prop", "revisions")
                        .param("rvprop", "content")
                        .param("formatversion", "2")
                        .param("titles", "Template:" + name))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("模板:" + name)))
                .andExpect(content().string(containsString(body)));

        // Action API：action=parse 的 page= 参数
        mockMvc.perform(get("/api.php").param("action", "parse")
                        .param("page", "Template:" + name))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(body)));
    }

    // ==================== 工具 ====================

    /** 每次运行都不重名的标题片段。 */
    private static String uniqueName() {
        return "别名归一" + System.nanoTime();
    }

    /** 前缀的大小写变体（中文前缀不变）：{@code User:} → {@code user:} / {@code USER:}。 */
    private static Set<String> spellings(String prefix) {
        Set<String> values = new LinkedHashSet<>();
        values.add(prefix);
        values.add(prefix.toLowerCase(Locale.ROOT));
        values.add(prefix.toUpperCase(Locale.ROOT));
        return values;
    }

    /** 用户 / 用户讨论命名空间需要真实账号（账号不存在时用户页按「不存在」处理）。 */
    private void ensureAccount(WikiNamespaces.Namespace namespace, String name) {
        if (!namespace.id().startsWith("user")) {
            return;
        }
        WikiUser user = new WikiUser();
        user.setUsername(name);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
    }

    /** 标题命中的实际落库标题（没有命中返回 null）。 */
    private String storedTitleOf(String title) {
        return wikiPageService.getByTitle(title).map(WikiPage::getTitle).orElse(null);
    }

    /** 打开标题对应的页面并断言能看到正文；分类 / 文件别名沿用既有行为（302 到规范名）时跟随重定向。 */
    private void assertPageBody(String title) throws Exception {
        MvcResult result = mockMvc.perform(get("/wiki/{title}", title)).andReturn();
        int status = result.getResponse().getStatus();
        if (status >= 300 && status < 400) {
            String location = result.getResponse().getRedirectedUrl();
            assertNotNull(location, title + " 的别名跳转应有 Location");
            mockMvc.perform(get(URI.create(location)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(MARKER)));
            return;
        }
        assertEquals(200, status, title + " 应直接打开规范页（或 302 到规范页）");
        assertTrue(result.getResponse().getContentAsString().contains(MARKER),
                title + " 的正文里应能看到页面内容");
    }
}
