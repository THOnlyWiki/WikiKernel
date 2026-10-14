package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 侧栏「工具箱」（仿 MediaWiki）：
 * <ul>
 *     <li>内容页工具：链入页面 / 相关更改 / 上传文件 / 特殊页面 / 可打印版 / 固定链接 / 页面信息，
 *     其中带参数的链接指向当前页面，且不会重复出现（旧侧栏里的裸链接会被替换）；</li>
 *     <li>{@code ?printable=yes}：只保留正文（隐藏站点界面）；</li>
 *     <li>{@code ?oldid=<当前版本>}（固定链接）：按最新版展示，不提示“旧版本”；更早的版本仍提示；</li>
 *     <li>特殊页没有页面上下文：只保留上传文件 / 特殊页面；</li>
 *     <li>支持 MediaWiki 子页语法：{@code 特殊:链入页面/页面标题}、{@code 特殊:用户贡献/用户名}。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class SidebarToolsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private UserService userService;

    private static String q(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static int occurrences(String html, String needle) {
        return html.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void contentPageToolboxHasFullMediaWikiTools() throws Exception {
        String target = "工具目标页" + System.nanoTime();
        String source = "工具来源页" + System.nanoTime();
        wikiPageService.editPage(null, target, "目标内容");
        Long firstRevision = wikiPageService.getByTitle(target).orElseThrow().getLatestRevisionId();
        wikiPageService.editPage(null, target, "第二版内容");
        Long latestRevision = wikiPageService.getByTitle(target).orElseThrow().getLatestRevisionId();
        wikiPageService.editPage(null, source, "[[" + target + "]]");

        String html = mockMvc.perform(get("/wiki/{title}", target))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String encoded = q(target);
        // 完整工具箱（MediaWiki 的七项；旧侧栏的同名裸链接被替换，不会重复出现）
        assertTrue(html.contains("?page=" + encoded), "链入 / 相关 / 页面信息应带当前页面标题");
        assertTrue(html.contains("?printable=yes"), "应有可打印版链接");
        assertTrue(html.contains("?oldid=" + latestRevision), "固定链接应指向当前版本");
        for (String label : new String[]{"链入页面", "相关更改", "上传文件", "可打印版", "固定链接", "页面信息"}) {
            assertEquals(1, occurrences(html, ">" + label + "</a>"), label + " 在工具箱里应恰好出现一次");
        }
        // 导航分组里也可能有「特殊页面」入口，这里只要求工具箱里存在
        assertTrue(html.contains(">特殊页面</a>"), "工具箱应包含特殊页面");

        // 点击链入页面：能列出链入的页面
        mockMvc.perform(get("/wiki/{title}", "特殊:链入页面").param("page", target))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(source)));

        // MediaWiki 子页语法：/wiki/特殊:链入页面/页面标题
        mockMvc.perform(get(URI.create("/wiki/" + q("特殊:链入页面") + "/" + encoded)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(source)));

        // 空查询：仍显示查找表单（仿 MediaWiki）
        mockMvc.perform(get("/wiki/{title}", "特殊:链入页面"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"page\"")))
                .andExpect(content().string(containsString("隐藏嵌入")));

        // 固定链接：指向当前版本 → 按最新版展示，不提示“旧版本”
        String permanent = mockMvc.perform(get("/wiki/{title}", target).param("oldid", String.valueOf(latestRevision)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(permanent.contains("第二版内容"), "固定链接应显示当前内容");
        assertFalse(permanent.contains("old-revision-warning"), "当前版本的固定链接不应提示旧版本");

        // 更早的版本仍按旧版本展示（保留提示）
        String older = mockMvc.perform(get("/wiki/{title}", target).param("oldid", String.valueOf(firstRevision)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(older.contains("目标内容"), "旧版本应显示当时内容");
        assertTrue(older.contains("old-revision-warning"), "旧版本应有提示");

        // 可打印版：隐藏站点界面，只留正文
        String printable = mockMvc.perform(get("/wiki/{title}", target).param("printable", "yes"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(printable.contains("wiki-printable"), "可打印版应有 wiki-printable 样式类");
        assertTrue(printable.contains("第二版内容"), "可打印版应包含正文");
        assertFalse(printable.contains("id=\"wiki-head\""), "可打印版不应显示顶部栏");
        assertFalse(printable.contains("id=\"wiki-panel\""), "可打印版不应显示侧栏");
        assertFalse(printable.contains("class=\"wiki-tabs-row\""), "可打印版不应显示页签行");

        // 英文界面：替换按链接目标识别（不依赖中文标签），旧的裸链接不残留
        String english = mockMvc.perform(get("/wiki/{title}", target).param("uselang", "en"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(english.contains("What links here"), "英文界面应显示英文工具名");
        assertTrue(english.contains("?page=" + encoded), "英文界面工具同样带当前标题");
        assertFalse(english.contains("href=\"/wiki/特殊:链入页面\""), "旧侧栏的裸链入页面链接应被替换");
    }

    @Test
    void specialPageSidebarUsesSpecialContext() throws Exception {
        String html = mockMvc.perform(get("/wiki/{title}", "特殊:最近更改"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 特殊页也要有完整工具箱（含 可打印版 / 固定链接 / 页面信息），且各出现一次
        for (String label : new String[]{"链入页面", "相关更改", "可打印版", "固定链接", "页面信息"}) {
            assertEquals(1, occurrences(html, ">" + label + "</a>"), label + " 在特殊页工具箱里应恰好出现一次");
        }
        assertTrue(html.contains(">上传文件</a>"), "特殊页保留上传文件");
        assertTrue(html.contains(">特殊页面</a>"), "特殊页保留特殊页面");
        assertTrue(html.contains("?printable=yes"), "可打印版应指向当前特殊页");
        assertTrue(html.contains("?action=info"), "页面信息应指向当前特殊页");
    }

    @Test
    void printablePageLoadsAutoPrintScript() throws Exception {
        String target = "打印版测试页" + System.nanoTime();
        wikiPageService.editPage(null, target, "打印内容");

        String printable = mockMvc.perform(get("/wiki/{title}", target).param("printable", "yes"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(printable.contains("printable.js"), "可打印版应加载自动打印脚本");
        assertTrue(printable.contains("printablePrint"), "可打印版应带上打印按钮文案");
        assertTrue(printable.contains("wiki-printable"), "可打印版应有样式类");

        String normal = mockMvc.perform(get("/wiki/{title}", target))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(normal.contains("printable.js"), "普通页面不应加载自动打印脚本");

        // 脚本本身：资源就绪后调用 window.print()（浏览器打开打印窗口，可另存为 PDF）
        String script = mockMvc.perform(get("/js/printable.js"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(script.contains("window.print()"), "脚本应调用 window.print()");
        assertTrue(script.contains("wk-printable-bar"), "脚本应插入打印 / 返回工具条");
        assertTrue(script.contains("wiki-printable"), "脚本应只在可打印版页面生效");
        assertTrue(script.contains("visibilitychange"), "页面不可见时应等切到前台再自动打印（避免「没有可用的预览」）");
    }

    @Test
    void contributionsSubpageSyntaxInjectsUser() throws Exception {
        WikiUser user = userService.createUser("侧栏贡献用户" + System.nanoTime(), "secret123", "");
        wikiPageService.editPage(user, "贡献子页" + System.nanoTime(), "贡献内容");
        mockMvc.perform(get(URI.create("/wiki/" + q("特殊:用户贡献") + "/" + q(user.getUsername()))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(user.getUsername())));
    }
}
