package cc.thonly.wikikernel.wikitext;

import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.plugin.Extension;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wikitext 渲染器单元测试（模板解析用内存桩，不依赖数据库）。 */
class WikitextRendererTest {

    private final Map<String, String> templates = Map.of(
            "模板:abc模板", "a={{{a|无}}}，b={{{b|无}}}，1={{{1|无}}}",
            "模板:循环", "{{模板:循环}}",
            "模板:文档", "正文<noinclude>（说明）</noinclude><includeonly>（包含）</includeonly>");

    private final WikitextRenderer renderer = new WikitextRenderer(
            new WikitextProperties(), title -> Optional.ofNullable(templates.get(title)));

    @Test
    void rendersInlineMarkup() {
        String html = renderer.render("'''粗体''' 和 ''斜体'' 和 '''''两者'''''", "测试");
        assertTrue(html.contains("<b>粗体</b>"), html);
        assertTrue(html.contains("<i>斜体</i>"), html);
        assertTrue(html.contains("<b><i>两者</i></b>"), html);
    }

    @Test
    void rendersHeadingListAndParagraph() {
        String html = renderer.render("== 标题 ==\n\n第一段\n\n* 甲\n* 乙\n\n# 一\n# 二", "测试");
        assertTrue(html.contains("<h2 id=\"标题\">标题</h2>"), html);
        assertTrue(html.contains("<p>第一段</p>"), html);
        assertTrue(html.contains("<ul><li>甲</li><li>乙</li></ul>"), html);
        assertTrue(html.contains("<ol><li>一</li><li>二</li></ol>"), html);
    }

    @Test
    void tableAttributesAreRendered() {
        // {| 行上的 class / style（信息框模板依赖）：class 合并进默认 wikitable，style 安全过滤后保留
        String html = renderer.render(
                "{| class=\"infobox\" style=\"float: right; width: 280px;\"\n|-\n! 标题 !! 值\n|-\n| 类型 || 教学\n|}",
                "测试");
        assertTrue(html.contains("class=\"wikitable infobox\""), html);
        assertTrue(html.contains("style=\"float: right; width: 280px;\""), html);
        // 未写属性时保持默认
        String plain = renderer.render("{|\n|-\n| 单元格\n|}", "测试");
        assertTrue(plain.contains("<table class=\"wikitable\">"), plain);
    }

    @Test
    void rendersLinks() {
        String html = renderer.render("[[首页|回首页]] 和 [https://example.com 外链]", "测试");
        assertTrue(html.contains("<a href=\"/wiki/首页\">回首页</a>"), html);
        assertTrue(html.contains("href=\"https://example.com\""), html);
        assertTrue(html.contains("target=\"_blank\""), html);
    }

    @Test
    void expandsTemplateWithNamedArgs() {
        String html = renderer.render("{{abc模板|a=1|b=2}}", "测试");
        assertTrue(html.contains("a=1"), html);
        assertTrue(html.contains("b=2"), html);
    }

    @Test
    void expandsTemplateWithPositionalAndDefault() {
        String html = renderer.render("{{abc模板|第一个}}", "测试");
        assertTrue(html.contains("1=第一个"), html);
        assertTrue(html.contains("a=无"), html);
    }

    @Test
    void transclusionHandlesNoincludeAndIncludeonly() {
        String html = renderer.render("{{模板:文档}}", "测试");
        assertTrue(html.contains("（包含）"), html);
        assertFalse(html.contains("（说明）"), html);
    }

    @Test
    void missingTemplateBecomesRedLink() {
        String html = renderer.render("{{不存在的模板}}", "测试");
        assertTrue(html.contains("class=\"new\""), html);
        assertTrue(html.contains("模板:不存在的模板"), html);
    }

    @Test
    void templateLoopIsReported() {
        String html = renderer.render("{{模板:循环}}", "测试");
        // 没有 MessageSource（单元测试）时显示翻译键本身，不回落中文
        assertTrue(html.contains("wikitext.loop"), html);
    }

    @Test
    void parserFunctionIf() {
        assertTrue(renderer.render("{{#if: x | 有 | 无 }}", "测试").contains("有"));
        assertTrue(renderer.render("{{#if:  | 有 | 无 }}", "测试").contains("无"));
        assertTrue(renderer.render("{{#ifeq: a | a | 相同 | 不同 }}", "测试").contains("相同"));
        assertTrue(renderer.render("{{#switch: b | a=甲 | b=乙 | #default=其他 }}", "测试").contains("乙"));
    }

    @Test
    void allowsIframeAndEscapesScript() {
        String html = renderer.render(
                "<iframe src=\"https://example.com/v\" width=\"560\"></iframe> <script>alert(1)</script>", "测试");
        assertTrue(html.contains("<iframe src=\"https://example.com/v\" width=\"560\">"), html);
        assertFalse(html.contains("<script>"), html);
        assertTrue(html.contains("&lt;script&gt;"), html);
    }

    @Test
    void allowsBrAndEscapesUnknownTags() {
        String html = renderer.render("第一行<br>第二行 <unknown>x</unknown>", "测试");
        assertTrue(html.contains("<br>"), html);
        assertTrue(html.contains("&lt;unknown&gt;"), html);
    }

    @Test
    void nowikiKeepsMarkupLiteral() {
        String html = renderer.render("<nowiki>'''粗体''' {{abc模板}}</nowiki>", "测试");
        assertTrue(html.contains("'''粗体'''"), html);
        assertFalse(html.contains("<b>"), html);
        assertFalse(html.contains("a="), html);
    }

    @Test
    void rendersTableAndPre() {
        String table = renderer.render("{|\n! 列A !! 列B\n|-\n| 1 || 2\n|}", "测试");
        assertTrue(table.contains("<table"), table);
        assertTrue(table.contains("<th>列A</th>"), table);
        assertTrue(table.contains("<td>1</td>"), table);

        String pre = renderer.render("<pre>a < b</pre>", "测试");
        assertTrue(pre.contains("<pre>a &lt; b</pre>"), pre);
    }

    @Test
    void magicWords() {
        String html = renderer.render(
                "页面：{{PAGENAME}} 完整：{{FULLPAGENAME}} 命名空间：{{NAMESPACE}} 子页：{{SUBPAGENAME}} 基础：{{BASEPAGENAME}} 根：{{ROOTPAGENAME}}",
                "帮助:格式/子页");
        assertTrue(html.contains("页面：格式/子页"), html);
        assertTrue(html.contains("完整：帮助:格式/子页"), html);
        assertTrue(html.contains("命名空间：帮助"), html);
        assertTrue(html.contains("子页：子页"), html);
        assertTrue(html.contains("基础：帮助:格式"), html);
        assertTrue(html.contains("根：格式"), html);

        String encoded = renderer.render("{{PAGENAMEE}}|{{FULLPAGENAMEE}}|{{NAMESPACEE}}", "A B:测试 页");
        assertTrue(encoded.contains("%E6%B5%8B%E8%AF%95_%E9%A1%B5"), encoded);
        assertTrue(encoded.contains("A_B%3A%E6%B5%8B%E8%AF%95_%E9%A1%B5"), encoded);
        assertTrue(encoded.contains("|A_B<"), encoded);

        String time = renderer.render("{{CURRENTYEAR}}", "测试");
        assertTrue(time.contains(String.valueOf(LocalDateTime.now().getYear())), time);
    }

    @Test
    void displayTitleMagicWordIsStrippedAndReported() {
        WikitextRenderer.RenderResult result = renderer.renderWithMeta(
                "你好{{DISPLAYTITLE:测试别名}}世界", "真实标题");
        assertEquals("测试别名", result.displayTitle());
        assertFalse(result.html().contains("DISPLAYTITLE"), result.html());
        assertTrue(result.html().contains("你好世界"), result.html());
    }

    @Test
    void directRenderDoesNotLeakIncludeonly() {
        String html = renderer.render("正文<includeonly>（仅包含时显示）</includeonly><noinclude>（仅页面显示）</noinclude>", "测试");
        assertNotNull(html);
        assertFalse(html.contains("仅包含时显示"), html);
        assertTrue(html.contains("仅页面显示"), html);
    }

    @Test
    void htmlCommentsAreStrippedButNowikiCommentsSurvive() {
        String html = renderer.render("前<!-- 去掉我 -->后\n\n* 甲<!-- 去掉我2 -->\n\n<nowiki><!-- 留下我 --></nowiki>", "测试");
        assertFalse(html.contains("去掉我"), html);
        assertTrue(html.contains("前后"), html);
        // nowiki 里的注释原样保留（转义显示）
        assertTrue(html.contains("留下我"), html);
    }

    @Test
    void inlineTokensDoNotCollideWithDigitContent() {
        // 回归：数字正文恰好夹在两个占位符之间时，逐号 replace 会跨边界误匹配（曾把表格 <td>30</td> 吞掉，
        // 还原出 \uE00133</td>34\uE001 这种残渣）。现在必须一次性正则还原。
        String card = "<div class=\"wiki-warning\"><b>标题</b><table class=\"wikitable\">"
                + "<tr><td>甲</td><td>52</td></tr>"
                + "<tr><td>乙</td><td>89</td></tr>"
                + "<tr><td>丙</td><td>69</td></tr>"
                + "<tr><td>丁</td><td>30</td></tr>"
                + "</table></div>";
        String html = renderer.render("前文。\n\n" + card + "\n\n后文。", "测试");
        assertTrue(html.contains("<td>30</td>"), html);
        assertTrue(html.contains("<td>丁</td>"), html);
        assertFalse(html.contains("\uE001"), html);
    }

    @Test
    void preBlockTokensDoNotCollideWithDigitContent() {
        String html = renderer.render("<pre>甲</pre>1<pre>乙</pre>1<pre>丙</pre>1<pre>丁</pre>", "测试");
        assertTrue(html.contains("<pre>甲</pre>") && html.contains("<pre>丁</pre>"), html);
        assertFalse(html.contains("\uE000"), html);
    }

    @Test
    void poemKeepsLineBreaksAndParsesInlineMarkup() {
        String html = renderer.render("<poem>\n第一行 '''粗体'''\n第二行 [[首页|链接]]\n</poem>", "测试");
        assertTrue(html.contains("<div class=\"poem\">"), html);
        assertTrue(html.contains("第一行 <b>粗体</b>"), html);
        assertTrue(html.contains("第一行 <b>粗体</b><br>"), html);
        assertTrue(html.contains("<a href=\"/wiki/首页\">链接</a>"), html);
    }

    @Test
    void galleryRendersItemsAndRedLinks() {
        // 每行一张图：文件:前缀可写可不写；不存在的文件显示红链；标签本身从正文消失
        String html = renderer.render("<gallery>\n文件:不存在的图.png|说明一\n也不存在.png\n</gallery>", "测试");
        assertTrue(html.contains("<ul class=\"gallery\">"), html);
        assertTrue(html.contains("class=\"gallerybox gallery-missing\""), html);
        assertTrue(html.contains("文件:不存在的图.png"), html);
        assertFalse(html.contains("<gallery"), html);
        // 属性：perrow（网格列数）、caption（画廊说明）、mode（nolines）
        String attrs = renderer.render(
                "<gallery perrow=\"3\" mode=\"nolines\" caption=\"'说明'组\">\n甲.png\n</gallery>", "测试");
        assertTrue(attrs.contains("gallery-perrow") && attrs.contains("mw-gallery-nolines"), attrs);
        assertTrue(attrs.contains("grid-template-columns: repeat(3, minmax(0, 1fr))"), attrs);
        assertTrue(attrs.contains("gallery-caption"), attrs);
    }

    @Test
    void indicatorIsStrippedAndStoredAsPageProperty() {
        WikiRenderContext context = renderer.createContext("测试");
        String html = renderer.renderWithMeta(
                "正文<indicator name=\"样例\">[[文件:图.png|20px]]</indicator>结尾", "测试", context).html();
        assertFalse(html.contains("<indicator"), html);
        assertTrue(html.contains("正文") && html.contains("结尾"), html);
        String value = context.property("indicator-样例", "");
        assertTrue(value.contains("图.png"), value);
        // 没有 name 的指示器照旧剥离，但不写页面属性
        WikiRenderContext second = renderer.createContext("测试");
        renderer.renderWithMeta("<indicator>裸内容</indicator>", "测试", second);
        assertTrue(second.properties().isEmpty(), second.properties().toString());
    }

    @Test
    void syntaxHighlightDropsTheNewlineRightAfterTheOpeningTag() {
        // 大家习惯 <syntaxhighlight lang="bash"> 后换行再写第一行：那个换行不该渲染成一行空白
        String bash = renderer.render("<syntaxhighlight lang=\"bash\">\n"
                + "# 检查版本\njava -version\njavac -version\n</syntaxhighlight>", "测试");
        assertTrue(bash.contains("<span class=\"hl-comment\"># 检查版本</span>"), bash);
        assertFalse(bash.contains("<code>\n"), "开标签后的换行应被剥掉：" + bash);
        assertFalse(bash.contains("\n</code>"), "闭标签前的换行应被剥掉：" + bash);
        // 中间的空行与首行缩进保留
        String kept = renderer.render("<syntaxhighlight lang=\"bash\">\n"
                + "  indented\n\nlast\n</syntaxhighlight>", "测试");
        assertTrue(kept.contains("indented") && kept.contains("last"), kept);
        assertTrue(kept.contains("\n\n"), "中间空行应保留：" + kept);
        // 一行写法不受影响
        String oneLine = renderer.render("<syntaxhighlight lang=\"java\">int x = 1;</syntaxhighlight>", "测试");
        assertTrue(oneLine.contains("<code>") && oneLine.contains("x"), oneLine);
    }

    @Test
    void preDropsTheSingleNewlineRightAfterTheOpeningTag() {
        // MediaWiki 规则：<pre> 后紧跟的一个换行被忽略，其余内容原样保留
        String html = renderer.render("<pre>\na < b\n</pre>", "测试");
        assertTrue(html.contains("<pre>a &lt; b"), html);
        assertFalse(html.contains("<pre>\n"), "开标签后的换行应被剥掉：" + html);
    }

    @Test
    void syntaxHighlightColorsCodeByLanguage() {
        String java = renderer.render(
                "<syntaxhighlight lang=\"java\">\n// 注释\nint x = 42;\nString s = \"hi\";\n</syntaxhighlight>", "测试");
        assertTrue(java.contains("<pre class=\"syntax\" data-lang=\"java\">"), java);
        assertTrue(java.contains("<span class=\"hl-comment\">// 注释</span>"), java);
        assertTrue(java.contains("<span class=\"hl-keyword\">int</span>"), java);
        assertTrue(java.contains("<span class=\"hl-number\">42</span>"), java);
        assertTrue(java.contains("<span class=\"hl-string\">&quot;hi&quot;</span>"), java);

        // <source> 是别名标签
        String source = renderer.render(
                "<source lang=\"python\">def f():\n    return True\n</source>", "测试");
        assertTrue(source.contains("data-lang=\"python\""), source);
        assertTrue(source.contains("<span class=\"hl-keyword\">def</span>"), source);
        assertTrue(source.contains("<span class=\"hl-literal\">True</span>"), source);

        // 未知语言 / 没有 lang：只转义，不着色
        String plain = renderer.render("<syntaxhighlight>a < b</syntaxhighlight>", "测试");
        assertTrue(plain.contains("<pre class=\"syntax\"><code>a &lt; b</code></pre>"), plain);
        assertFalse(plain.contains("hl-keyword"), plain);
    }

    @Test
    void mathAndChemSurviveNowikiTagNamesInHeading() {
        // 复杂组合页：标题与正文里只是「提到」标签名（用 nowiki 保护）+ 后面真正的公式标签。
        // 回归：标题里裸写 <chem> 会被当成真标签，一直吞到下一个 </chem>（与 MediaWiki 一致），
        // 所以词条 / 演示页要写 <nowiki><chem></nowiki>；这里保证这样写时标题、正文都正常。
        // 注：Math 已迁到插件 math——core 未加载插件时公式标签按原文转义显示（见插件测试 MathTagTest）。
        String wikitext = """
                == 化学式（<nowiki><chem></nowiki>） ==
                正文提到 <nowiki><chem></nowiki>、<nowiki></chem></nowiki> 与 <nowiki><math></math></nowiki> 时用 nowiki 包住。

                * 水的合成：<chem>2H_2 + O_2 -> 2H_2O</chem>
                * 硫酸：<chem>H_2SO_4</chem>
                * 质能方程：<math>E = mc^2</math>
                """;
        String html = renderer.render(wikitext, "数学公式演示页");

        // 标题还在，标签名按字面转义显示，没有被吞进公式
        assertTrue(html.contains(">化学式（&lt;chem&gt;）</h2>"), html);
        assertTrue(html.contains("<h2 id="), html);
        // 公式标签已不在 core 渲染：按原文转义显示（插件加载时由 math 插件生成占位）
        assertFalse(html.contains("data-tex=\""), html);
        assertTrue(html.contains("&lt;chem&gt;2H_2 + O_2 -&gt; 2H_2O&lt;/chem&gt;"), html);
        // 正文里的 nowiki 也按字面显示（不会变成真标签，也不会吞后面的内容）
        assertTrue(html.contains("&lt;chem&gt;、&lt;/chem&gt;"), html);
        assertTrue(html.contains("&lt;math&gt;&lt;/math&gt;"), html);
    }

    @Test
    void pageBannerDirectiveIsNotHandledByCore() {
        // 页面横幅已迁到插件 page-banner：core 不再抽取 {{PAGEBANNER:…}}（未注册的指令按模板解析），
        // nowiki 里的写法示例照旧按字面显示
        WikitextRenderer.RenderResult result = renderer.renderWithMeta(
                "讲解写法：<nowiki>{{PAGEBANNER:示例.png}}</nowiki>\n\n正文", "测试");
        assertTrue(result.html().contains("讲解写法：{{PAGEBANNER:示例.png}}"), result.html());
        assertFalse(result.html().contains("class=\"page-banner\""), result.html());

        // <exclude-banner-page /> 也不再由 core 剥离：插件未加载时按原文转义显示
        String excluded = renderer.render("<exclude-banner-page />\n\n正文", "测试");
        assertTrue(excluded.contains("&lt;exclude-banner-page /&gt;"), excluded);
        assertFalse(excluded.contains("class=\"page-banner\""), excluded);
    }

    /** 插件的无 {@code #} 解析器函数（{@code {{PAGEBANNER:…}}}）由 core 按名字派发。 */
    @Test
    void dispatchesColonParserFunctionsToPlugins() throws Exception {
        WikitextRenderer withPlugin = new WikitextRenderer(new WikitextProperties(), title -> Optional.empty());
        Field field = WikitextRenderer.class.getDeclaredField("pluginManager");
        field.setAccessible(true);
        field.set(withPlugin, pluginManagerWith(new ParserFunction() {
            @Override
            public String name() {
                return "PAGEBANNER";
            }

            @Override
            public String invoke(ParserFunctionCall call) {
                call.context().setProperty("demo-file", call.args().isEmpty() ? "" : call.args().get(0).trim());
                return "";
            }
        }));

        WikiRenderContext context = withPlugin.createContext("测试");
        WikitextRenderer.RenderResult result = withPlugin.renderWithMeta(
                "{{PAGEBANNER:横幅.png|caption=说明}}\n\n正文", "测试", context);
        assertEquals("横幅.png", context.properties().get("demo-file"));
        assertFalse(result.html().contains("PAGEBANNER"), result.html());
        assertTrue(result.html().contains("正文"), result.html());

        // 没有注册同名函数时仍按模板解析（红链），不会被误当成插件指令
        assertTrue(renderer.render("{{PAGEBANNER:横幅.png}}", "测试").contains("PAGEBANNER:横幅.png"));
    }

    @Test
    void markdownTagRendersMarkdownSubset() {
        String html = renderer.render("""
                <markdown>
                # 标题

                **粗体** 与 `代码` 与 ~~删除~~

                - 甲
                - 乙

                ```java
                int x = 42;
                ```

                | 列 | 值 |
                | --- | --- |
                | a | b |
                </markdown>
                """, "测试");

        assertTrue(html.contains("<div class=\"markdown\">"), html);
        assertTrue(html.contains("<h1>标题</h1>"), html);
        assertTrue(html.contains("<strong>粗体</strong>"), html);
        assertTrue(html.contains("<code>代码</code>"), html);
        assertTrue(html.contains("<del>删除</del>"), html);
        assertTrue(html.contains("<li>甲</li>"), html);
        // 围栏代码块复用项目的语法高亮结构
        assertTrue(html.contains("<pre class=\"syntax\" data-lang=\"java\">"), html);
        assertTrue(html.contains("<span class=\"hl-keyword\">int</span>"), html);
        assertTrue(html.contains("<th>列</th>"), html);
        // 开标签后的那个换行不该渲染成开头空行
        assertFalse(html.contains("<div class=\"markdown\">\n"), html);
    }

    @Test
    void markdownTagKeepsWikitextAsRawText() {
        String html = renderer.render("<markdown>\n[[首页|链接]] 与 {{模板}} 与 ''斜体''\n</markdown>", "测试");

        assertTrue(html.contains("[[首页|链接]]"), html);
        assertTrue(html.contains("{{模板}}"), html);
        // wikitext 的粗斜体标记不生效（Markdown 里 '' 不是标记）
        assertFalse(html.contains("<i>斜体</i>"), html);
        assertFalse(html.contains("<a href=\"/wiki/首页\">"), html);
    }

    @Test
    void markdownTagFiltersInlineHtmlAndDangerousLinks() {
        // 普通段落里的行内 HTML 与链接
        String html = renderer.render(
                "<markdown>\n行内 <b>粗体</b> 与 [点我](https://example.com) 与 [危险](javascript:alert(1))\n</markdown>",
                "测试");

        assertTrue(html.contains("<b>粗体</b>"), html);
        // javascript: 地址不生成 <a>，正常链接保留
        assertFalse(html.contains("javascript:"), html);
        assertTrue(html.contains("href=\"https://example.com\""), html);

        // <script> 不在白名单：按字面转义显示（块级 / 行内两种写法都不会成为真标签）
        String block = renderer.render("<markdown>\n<script>alert(1)</script>\n</markdown>", "测试");
        assertFalse(block.contains("<script"), block);
        assertTrue(block.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), block);

        String inline = renderer.render("<markdown>\n正文 <script>alert(1)</script> 结尾\n</markdown>", "测试");
        assertFalse(inline.contains("<script"), inline);
        assertTrue(inline.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), inline);

        // 白名单内的行内 HTML 保留，class 属性附加到 markdown 容器上
        String allowed = renderer.render("<markdown class=\"note\">\n行内 <b>粗体</b>\n</markdown>", "测试");
        assertTrue(allowed.contains("<div class=\"markdown note\">"), allowed);
        assertTrue(allowed.contains("<b>粗体</b>"), allowed);
    }

    @Test
    void markdownTagStripsEdgeBlankLines() {
        // 开标签后紧跟的换行 + 首尾空行剥掉，中间的空行保留
        assertEquals("# 标题\n\n正文", WikitextRenderer.stripEdgeBlankLines("\n# 标题\n\n正文\n\n"));
        // 只剥「紧跟开标签的那一个换行」：<pre>\n\n正文 的首行空行要保留（与 MediaWiki 一致）
        assertEquals("\n正文", WikitextRenderer.stripEdgeBlankLines("\n\n正文"));
        assertEquals("Java", WikitextRenderer.stripEdgeBlankLines("Java"));
        assertEquals("", WikitextRenderer.stripEdgeBlankLines("\n\n\n"));

        String html = renderer.render("<markdown>\n\n# 标题\n\n\n</markdown>", "测试");
        assertTrue(html.contains("<div class=\"markdown\"><h1>标题</h1>"), html);
        assertFalse(html.contains("<div class=\"markdown\">\n"), html);
    }

    /** 只提供指定解析器函数的 {@link PluginManager} 测试替身（不启动任何插件容器）。 */
    private static PluginManager pluginManagerWith(ParserFunction function) {
        return new PluginManager(null, null, null, null) {
            @Override
            @SuppressWarnings("unchecked")
            public <T extends Extension> List<T> extensions(Class<T> extensionPoint) {
                if (extensionPoint == ParserFunction.class) {
                    return (List<T>) List.of(function);
                }
                return List.of();
            }
        };
    }

    /** 统计子串出现次数（公式数量断言用）。 */
    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }
}
