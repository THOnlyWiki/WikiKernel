package cc.thonly.wikikernel.plugin.templatedata.service;

import cc.thonly.wikikernel.plugin.templatedata.TemplateDataMessages;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TemplateDataHtmlFormatter}：模板文档表格的关键片段（参数名 / 别名 / 状态 / 类型 / 描述 /
 * 默认值 / 示例 / 推荐值 / 自动值）、格式提示、错误框与转义。
 */
class TemplateDataHtmlFormatterTest {

    private static final TemplateDataMessages ZH = TemplateDataMessages.of("zh");
    private static final TemplateDataMessages EN = TemplateDataMessages.of("en");

    @Test
    void minimalJsonRendersEmptyTable() {
        String html = html("{\"params\":{}}", ZH);
        assertTrue(html.startsWith("<section class=\"mw-templatedata-doc-wrap\"><header>"), html);
        assertTrue(html.contains("<p class=\"mw-templatedata-doc-desc mw-templatedata-doc-muted\">无描述。</p>"), html);
        assertTrue(html.contains("<table class=\"wikitable mw-templatedata-doc-params\">"), html);
        assertTrue(html.contains("<p class=\"mw-templatedata-caption\">模板参数</p>"), html);
        assertTrue(html.contains("<th colspan=\"2\">参数</th><th>描述</th><th>类型</th><th>状态</th>"), html);
        assertTrue(html.contains("<td class=\"mw-templatedata-doc-muted\" colspan=\"7\">未指定参数</td>"), html);
        // 没有设置 format 时没有格式提示，参数只有 0 个时不加 sortable
        assertFalse(html.contains("mw-templatedata-format"), html);
        assertFalse(html.contains("sortable"), html);
    }

    @Test
    void minimalJsonMatchesTheOriginalParserTestOutput() {
        // 与原版 tests/parser/parserTests.txt 的「Minimal JSON」用例逐字对齐（只少了 OOUI 图标与编辑链接）
        String html = html("{\"params\":{}}", EN);
        assertEquals("<section class=\"mw-templatedata-doc-wrap\"><header>"
                + "<p class=\"mw-templatedata-doc-desc mw-templatedata-doc-muted\">No description.</p></header>"
                + "<table class=\"wikitable mw-templatedata-doc-params\"><caption>"
                + "<p class=\"mw-templatedata-caption\">Template parameters</p></caption>"
                + "<thead><tr><th colspan=\"2\">Parameter</th><th>Description</th><th>Type</th><th>Status</th></tr></thead>"
                + "<tbody><tr><td class=\"mw-templatedata-doc-muted\" colspan=\"7\">No parameters specified</td></tr>"
                + "</tbody></table></section>", html);
    }

    @Test
    void parameterRowContainsNamesAliasesStatusAndType() {
        String html = html("""
                {
                  "description": "模板说明",
                  "params": {
                    "foo": {
                      "label": "标签",
                      "description": "参数说明",
                      "type": "line",
                      "required": true,
                      "aliases": ["f", "别名"],
                      "default": "默认",
                      "example": "示例",
                      "suggestedvalues": ["甲", "乙"],
                      "autovalue": "{{CURRENTYEAR}}"
                    },
                    "bar": { "suggested": true },
                    "baz": { "deprecated": "改用 bar" },
                    "qux": {}
                  }
                }
                """, ZH);

        // 描述与 sortable（参数多于 1 个）
        assertTrue(html.contains("<p class=\"mw-templatedata-doc-desc\">模板说明</p>"), html);
        assertTrue(html.contains("<table class=\"wikitable mw-templatedata-doc-params sortable\">"), html);

        // 参数名 + 别名
        assertTrue(html.contains("<code>foo</code> <code class=\"mw-templatedata-doc-param-alias\">f</code>"
                + " <code class=\"mw-templatedata-doc-param-alias\">别名</code>"), html);
        // 标签
        assertTrue(html.contains("<tr><th>标签</th>"), html);
        // 描述、推荐值、默认值、示例值、自动值都在同一格里
        assertTrue(html.contains("<p>参数说明</p><dl>"), html);
        assertTrue(html.contains("<dt>推荐值</dt><dd><code>甲</code> <code>乙</code></dd>"), html);
        assertTrue(html.contains("<dt>默认值</dt><dd>默认</dd>"), html);
        assertTrue(html.contains("<dt>示例值</dt><dd>示例</dd>"), html);
        assertTrue(html.contains("<dt>自动值</dt><dd><code>{{CURRENTYEAR}}</code></dd>"), html);
        // 类型与状态
        assertTrue(html.contains("<td class=\"mw-templatedata-doc-param-type\">单行文本</td>"), html);
        assertTrue(html.contains("<td class=\"mw-templatedata-doc-param-type mw-templatedata-doc-muted\">未知</td>"), html);
        assertTrue(html.contains("<td class=\"mw-templatedata-doc-param-status-required\" data-sort-value=\"2\">必需</td>"), html);
        assertTrue(html.contains("<td class=\"mw-templatedata-doc-param-status-suggested\" data-sort-value=\"1\">推荐</td>"), html);
        assertTrue(html.contains("<td class=\"mw-templatedata-doc-param-status-deprecated\" data-sort-value=\"-1\">弃用</td>"), html);
        assertTrue(html.contains("<td class=\"mw-templatedata-doc-param-status-optional\" data-sort-value=\"0\">可选</td>"), html);
        // 参数顺序就是 JSON 里的顺序
        assertTrue(html.indexOf("<code>foo</code>") < html.indexOf("<code>bar</code>"), html);
        // 没有描述的参数显示占位文本
        assertTrue(html.contains("<p class=\"mw-templatedata-doc-muted\">无描述</p>"), html);
    }

    @Test
    void paramOrderControlsTableOrder() {
        String html = html("""
                {
                  "params": { "foo": {}, "bar": {} },
                  "paramOrder": ["bar", "foo"]
                }
                """, ZH);
        assertTrue(html.indexOf("<code>bar</code>") < html.indexOf("<code>foo</code>"), html);
    }

    @Test
    void formatHintIsRendered() {
        assertTrue(html("{\"params\":{},\"format\":\"inline\"}", ZH)
                .contains("<span class=\"mw-templatedata-format\">此模板首选参数不换行的行内格式。</span>"));
        assertTrue(html("{\"params\":{},\"format\":\"block\"}", ZH)
                .contains("<span class=\"mw-templatedata-format\">此模板首选参数换行的代码块格式。</span>"));
        assertTrue(html("{\"params\":{},\"format\":\"{{_|_=_}}\"}", ZH)
                .contains("<span class=\"mw-templatedata-format\">此模板有自定义格式。</span>"));
    }

    @Test
    void englishMessagesAreAvailable() {
        String html = html("{\"description\":\"Demo\",\"params\":{\"foo\":{\"required\":true,\"type\":\"number\"}}}", EN);
        assertTrue(html.contains("<p class=\"mw-templatedata-doc-desc\">Demo</p>"), html);
        assertTrue(html.contains("<th colspan=\"2\">Parameter</th><th>Description</th><th>Type</th><th>Status</th>"), html);
        assertTrue(html.contains("<td class=\"mw-templatedata-doc-param-status-required\" data-sort-value=\"2\">required</td>"), html);
        assertTrue(html.contains(">Number</td>"), html);
    }

    @Test
    void userDataIsEscaped() {
        String json = "{\"description\":\"<script>alert(1)</script>\","
                + "\"params\":{\"foo<script>\":{"
                + "\"label\":\"\\\"标签\\\" & <b>粗体</b>\","
                + "\"description\":\"<img src=x onerror=alert(1)>\","
                + "\"aliases\":[\"<i>别名</i>\"]}}}";
        String html = html(json, ZH);
        assertFalse(html.contains("<script>"), html);
        assertFalse(html.contains("<img src=x"), html);
        assertFalse(html.contains("<b>粗体"), html);
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html);
        assertTrue(html.contains("<code>foo&lt;script&gt;</code>"), html);
        assertTrue(html.contains("<code class=\"mw-templatedata-doc-param-alias\">&lt;i&gt;别名&lt;/i&gt;</code>"), html);
        assertTrue(html.contains("&quot;标签&quot; &amp; &lt;b&gt;粗体&lt;/b&gt;"), html);
    }

    @Test
    void stylesAreStaticCssForTheWikimediaClassNames() {
        String styles = TemplateDataHtmlFormatter.styles();
        assertTrue(styles.startsWith("<style>"), styles);
        assertTrue(styles.contains(".mw-templatedata-doc-muted{font-style:italic;color:#555}"), styles);
        assertTrue(styles.contains(".mw-templatedata-doc-param-status-required{font-weight:bold}"), styles);
        assertTrue(styles.endsWith("</style>"), styles);
    }

    @Test
    void errorBoxUsesLocalizedMessage() {
        TemplateDataBlob blob = TemplateDataBlob.parse("{\"params\":[]}", "zh");
        assertFalse(blob.status().isOk());
        String zh = TemplateDataHtmlFormatter.errorBox(blob.status(), ZH);
        assertTrue(zh.equals("<div class=\"errorbox\"><p>属性“params”的类型应为“object”。</p></div>"), zh);

        String en = TemplateDataHtmlFormatter.errorBox(blob.status(), EN);
        assertTrue(en.contains("Property &quot;params&quot; is expected to be of type &quot;object&quot;."), en);

        TemplateDataBlob parseError = TemplateDataBlob.parse("{", "zh");
        assertTrue(TemplateDataHtmlFormatter.errorBox(parseError.status(), ZH).contains("JSON内有语法错误。"));
        // 原版 parserTests 里的英文文案（原版外层是 mw-message-box-error，本移植用 errorbox）
        assertEquals("<div class=\"errorbox\"><p>Syntax error in JSON.</p></div>",
                TemplateDataHtmlFormatter.errorBox(parseError.status(), EN));
    }

    private static String html(String json, TemplateDataMessages messages) {
        TemplateDataBlob blob = TemplateDataBlob.parse(json, messages.language());
        assertTrue(blob.status().isOk(), () -> "用例数据应合法：" + blob.status());
        return new TemplateDataHtmlFormatter(messages, messages.language()).getHtml(blob);
    }
}
