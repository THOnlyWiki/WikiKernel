package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JS / CSS 代码页面：查看时是代码预览（不再走 Wikitext），编辑时用代码编辑器，
 * CodeMirror 资源本地内置（/vendor/codemirror）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class CodePageTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private FeatureService featureService;
    @Autowired
    private SiteSettingsService siteSettingsService;


    @Test
    void jsAndCssPagesRenderAsCodePreview() throws Exception {
        wikiPageService.editPage(null, "代码预览.js", "function hi() { return 1; }");

        mockMvc.perform(get("/wiki/{title}", "代码预览.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"wiki-code\"")))
                .andExpect(content().string(containsString("data-language=\"javascript\"")))
                .andExpect(content().string(containsString("function hi() { return 1; }")))
                // 代码页面顶部有「清除浏览器缓存」提示（仿 MediaWiki:Clearyourcache）
                .andExpect(content().string(containsString("清除浏览器缓存")))
                .andExpect(content().string(containsString("Ctrl-Shift-R")))
                // 不再走 Wikitext（不会包 <p>）
                .andExpect(content().string(not(containsString("<p>function"))))
                // 代码页才加载内置 CodeMirror
                .andExpect(content().string(containsString("/vendor/codemirror/codemirror.min.js")));

        wikiPageService.editPage(null, "代码预览.css", ".a { color: red; }");

        mockMvc.perform(get("/wiki/{title}", "代码预览.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-language=\"css\"")))
                .andExpect(content().string(containsString(".a { color: red; }")))
                .andExpect(content().string(containsString("清除浏览器缓存")));

        // 普通 Wikitext 页面没有这条提示
        String plain = "非代码页" + System.nanoTime();
        wikiPageService.editPage(null, plain, "正文");
        mockMvc.perform(get("/wiki/{title}", plain))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("清除浏览器缓存"))));
    }

    @Test
    void codeEditorOnEditPageAndVendorAssetsBundled() throws Exception {
        mockMvc.perform(get("/vendor/codemirror/codemirror.min.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CodeMirror")));
        mockMvc.perform(get("/vendor/codemirror/mode-css.min.js"))
                .andExpect(status().isOk());

        WikiUser user = insertUser();
        // CSS / JS 页面默认只有界面管理员能编辑（见 InterfaceAdminTest）
        permissionService.replaceFor(user.getId(),
                java.util.List.of(PermissionService.GROUP_INTERFACE_ADMIN));
        wikiPageService.editPage(null, "编辑器测试.js", "let a = 1;");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());

        mockMvc.perform(get("/wiki/{title}", "编辑器测试.js").param("action", "edit").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-language=\"javascript\"")))
                .andExpect(content().string(containsString("/js/code-editor.js")))
                .andExpect(content().string(containsString("语法高亮与自动补全")))
                .andExpect(content().string(containsString("清除浏览器缓存")));

        // 只读预览用 readOnly: 'nocursor'：点击不出现光标
        mockMvc.perform(get("/js/code-editor.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("'nocursor'")));
    }

    @Test
    void typescriptPagesRenderAsCodePreviewWithHighlighting() throws Exception {
        // .ts / .tsx：代码内容页（高亮 + 编辑器 + 复制），不会被当成脚本执行
        wikiPageService.editPage(null, "代码预览.ts",
                "interface User { id: number }\nconst greet = (u: User): string => `hi ${u.id}`;");

        mockMvc.perform(get("/wiki/{title}", "代码预览.ts"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"wiki-code\"")))
                .andExpect(content().string(containsString("data-language=\"typescript\"")))
                .andExpect(content().string(containsString("interface User { id: number }")))
                // 代码页才加载内置 CodeMirror（前端用它做 TypeScript 高亮：text/typescript MIME）
                .andExpect(content().string(containsString("/vendor/codemirror/codemirror.min.js")))
                // 不走 Wikitext
                .andExpect(content().string(not(containsString("<p>interface"))));

        wikiPageService.editPage(null, "代码预览.tsx", "export const App = () => null;");
        mockMvc.perform(get("/wiki/{title}", "代码预览.tsx"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-language=\"typescript\"")));

        // 编辑页：代码编辑器 + 提示（不是 Wikitext 工具栏）
        WikiUser user = insertUser();
        permissionService.replaceFor(user.getId(), java.util.List.of(PermissionService.GROUP_ADMIN));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, user.toTransferCopy());
        mockMvc.perform(get("/wiki/{title}", "代码预览.ts").param("action", "edit").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-language=\"typescript\"")))
                .andExpect(content().string(containsString("语法高亮与自动补全")));

        // 页面信息里的「内容模型」
        mockMvc.perform(get("/wiki/{title}", "特殊:页面信息").param("page", "代码预览.ts"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("typescript")));

        // CodeMirror 的 JS 模式本身带 TypeScript 支持（零新增前端库）
        mockMvc.perform(get("/vendor/codemirror/mode-javascript.min.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("text/typescript")));
        // 代码编辑器脚本把 typescript 映射到这个 MIME
        mockMvc.perform(get("/js/code-editor.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("text/typescript")));
    }

    @Test
    void syntaxHighlightTagSupportsTypeScriptInCombinedPage() throws Exception {
        // 同页也会用到 Math（Math 开关可能被其它测试用例改动，这里显式打开）
        siteSettingsService.set("features.math", Boolean.toString(true));
        try {
            // 复杂组合页面：TypeScript 代码块 + JS 代码块 + Python + 表格 + 链接 + 数学
            String title = "代码高亮组合页" + System.nanoTime();
            wikiPageService.editPage(null, title, """
                == TypeScript ==
                <syntaxhighlight lang="ts">
                interface Point { x: number; y: number }
                const origin: Point = { x: 0, y: 0 };
                </syntaxhighlight>

                == JavaScript（对照） ==
                <syntaxhighlight lang="js">let n = 1;</syntaxhighlight>

                == Python（对照） ==
                <syntaxhighlight lang="python">def f(x): return x</syntaxhighlight>

                == 与其它扩展组合 ==
                行内数学 <math>a^2</math>，链接 [[首页]]，表格：

                {| class="wikitable"
                |-
                ! 列
                |-
                | 值
                |}
                """);

            mockMvc.perform(get("/wiki/{title}", title))
                    .andExpect(status().isOk())
                    // TypeScript 关键字 interface / const 被着色（服务端高亮，不需要浏览器）
                    .andExpect(content().string(containsString("<span class=\"hl-keyword\">interface</span>")))
                    .andExpect(content().string(containsString("<span class=\"hl-keyword\">const</span>")))
                    .andExpect(content().string(containsString("class=\"syntax\" data-lang=\"ts\"")))
                    .andExpect(content().string(containsString("Point")))
                    // 对照：JS / Python 代码块同样高亮
                    .andExpect(content().string(containsString("<span class=\"hl-keyword\">let</span>")))
                    .andExpect(content().string(containsString("<span class=\"hl-keyword\">def</span>")))
                    // Math 已迁到插件 math：core 未加载插件时公式标签按原文转义显示
                    .andExpect(content().string(containsString("&lt;math&gt;")))
                    .andExpect(content().string(containsString("class=\"wikitable\"")));
        } finally {
            siteSettingsService.set("features.math", Boolean.toString(true));
        }
    }

    private WikiUser insertUser() {
        WikiUser user = new WikiUser();
        user.setUsername("代码测试用户" + System.nanoTime());
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }
}
