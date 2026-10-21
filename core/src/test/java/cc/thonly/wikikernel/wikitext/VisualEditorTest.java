package cc.thonly.wikikernel.wikitext;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可视化编辑器：
 * <ul>
 *     <li>受支持子集（段落 / 标题 / 粗斜体 / 链接 / 列表 / 缩进 / 水平线 / 简单表格）能精确往返；</li>
 *     <li>模板 / 引用 / 文件 / 分类 / 预格式等变成源码块，往返后原样保留；</li>
 *     <li>目录、分类栏等「生成内容」绝不进入可视化编辑；</li>
 *     <li>不支持的结构整体拒绝（绝不写回半成品）。</li>
 * </ul>
 */
class VisualEditorTest {

    /** 测试用模板仓库（按标题）。 */
    private final java.util.Map<String, String> templateMap = new java.util.LinkedHashMap<>();

    private final WikitextProperties properties = new WikitextProperties();

    private final TemplateResolver resolver = title -> Optional.ofNullable(templateMap.get(title));

    private final VisualEditorService service = new VisualEditorService(
            new WikitextRenderer(properties, resolver), resolver, properties,
            new org.springframework.context.support.StaticMessageSource());

    private String roundTrip(String wikitext) {
        VisualEditorService.Rendered rendered = service.render(wikitext, "测试页");
        return service.serialize(rendered.html(), rendered.tokens());
    }

    // ==================== 往返（无修改时源码保持不变） ====================

    @Test
    void roundTripsHeadingsAndParagraphs() {
        String source = "== 二级 ==\n\n=== 三级 ===\n\n普通段落一。\n\n普通段落二。\n";
        assertEquals(source, roundTrip(source));
    }

    @Test
    void roundTripsTextFormatting() {
        assertEquals("'''粗体''' 与 ''斜体'' 与 '''''双'''''。\n",
                roundTrip("'''粗体''' 与 ''斜体'' 与 '''''双'''''。\n"));
    }

    @Test
    void roundTripsLinks() {
        String source = "[[首页]]、[[首页|显示文字]]、[[首页#段落]]、[[首页#段落|跳转]]"
                + " 与 [https://example.com 示例]、[https://example.com]。\n";
        assertEquals(source, roundTrip(source));
    }

    @Test
    void roundTripsListsAndIndent() {
        String source = "* 甲\n* 乙\n** 乙一\n** 乙二\n\n# 一\n# 二\n## 二一\n\n: 缩进\n; 术语 : 定义\n";
        assertEquals(source, roundTrip(source));
    }

    @Test
    void roundTripsHorizontalRuleAndBreaks() {
        String source = "上行\n\n----\n\n下行<br>换行\n";
        assertEquals(source, roundTrip(source));
    }

    @Test
    void roundTripsSimpleTable() {
        String source = "{| class=\"wikitable\"\n|-\n! 表头A !! 表头B\n|-\n| 单元A || 单元B\n|}\n";
        assertEquals(source, roundTrip(source));
    }

    // ==================== 生成内容不进编辑器 ====================

    @Test
    void generatedTocNeverEntersEditor() {
        String source = "== 一 ==\n\n=== 一一 ===\n\n== 二 ==\n\n== 三 ==\n\n正文。\n";
        VisualEditorService.Rendered rendered = service.render(source, "测试页");
        assertFalse(rendered.html().contains("id=\"toc\""), rendered.html());
        assertFalse(rendered.html().contains("class=\"toc\""), rendered.html());
        assertFalse(rendered.html().contains("toctitle"), rendered.html());
        // 标题本身还在，且往返不丢目录相关源码
        assertTrue(rendered.html().contains("<h2"), rendered.html());
        assertEquals(source, service.serialize(rendered.html(), rendered.tokens()));
    }

    @Test
    void categoriesBecomeSourceBlocksAndCatlinksAreStripped() {
        String source = "正文。\n\n[[分类:测试分类|排序]]\n";
        VisualEditorService.Rendered rendered = service.render(source, "测试页");
        assertFalse(rendered.html().contains("catlinks"), rendered.html());
        assertTrue(rendered.html().contains("ve-token"), rendered.html());
        assertEquals(source, service.serialize(rendered.html(), rendered.tokens()));
    }

    // ==================== 复杂内容：源码块原样保留 ====================

    @Test
    void templatesRefsFilesAndPreSurviveVisualEditing() {
        String source = "文字<ref>来源A</ref>结束。\n\n{{模板:信息框|name=甲}}\n\n"
                + "[[文件:图.png|缩略图|说明]]\n\n<pre>代码 &amp; 内容</pre>\n\n<nowiki>[[不解析]]</nowiki>\n";
        VisualEditorService.Rendered rendered = service.render(source, "测试页");
        // 5 个源码块：ref / 模板 / 文件 / pre / nowiki
        assertTrue(rendered.tokens().size() >= 5, rendered.tokens().toString());
        assertTrue(rendered.html().contains("ve-token"), rendered.html());
        assertEquals(source, service.serialize(rendered.html(), rendered.tokens()));
    }

    @Test
    void complexTableBecomesSourceBlock() {
        String source = "{| class=\"wikitable\"\n|-\n! A\n|-\n| colspan=\"2\" | B\n|}\n";
        VisualEditorService.Rendered rendered = service.render(source, "测试页");
        assertTrue(rendered.tokens().contains(source.strip()), rendered.tokens().toString());
        assertEquals(source, service.serialize(rendered.html(), rendered.tokens()));
    }

    @Test
    void editedTokenSourceIsApplied() {
        String source = "{{模板|a=1}}\n";
        VisualEditorService.Rendered rendered = service.render(source, "测试页");
        List<String> tokens = new ArrayList<>(rendered.tokens());
        tokens.set(0, "{{模板|a=2}}");
        assertEquals("{{模板|a=2}}\n", service.serialize(rendered.html(), tokens));
    }

    // ==================== 编辑后的序列化 ====================

    @Test
    void serializesEditedText() {
        VisualEditorService.Rendered rendered = service.render("旧文字。\n", "测试页");
        var document = Jsoup.parseBodyFragment(rendered.html());
        document.body().selectFirst("p").text("新文字。");
        assertEquals("新文字。\n", service.serialize(document.body().html(), rendered.tokens()));
    }

    @Test
    void serializesFormattingAndLinksDirectly() {
        String html = "<p><b>粗体</b> 与 <i>斜体</i> 与 <a href=\"/wiki/%E9%A6%96%E9%A1%B5\">首页</a>"
                + " 与 <a href=\"https://example.com\">示例</a>。</p>";
        assertEquals("'''粗体''' 与 ''斜体'' 与 [[首页]] 与 [https://example.com 示例]。\n",
                service.serialize(html, List.of()));
    }

    @Test
    void serializesListsWithBrowserNesting() {
        String html = "<ul><li>甲</li><li>乙<ul><li>乙一</li></ul></li></ul>";
        assertEquals("* 甲\n* 乙\n** 乙一\n", service.serialize(html, List.of()));
    }

    @Test
    void serializesBrowserDivParagraphs() {
        String html = "<div>第一</div><div>第二</div>";
        assertEquals("第一\n\n第二\n", service.serialize(html, List.of()));
    }

    @Test
    void serializesChipsBackToSource() {
        String html = "<p>甲<span class=\"ve-token\" data-token=\"0\">ref</span>乙</p>";
        assertEquals("甲<ref>来源</ref>乙\n", service.serialize(html, List.of("<ref>来源</ref>")));
    }

    // ==================== 安全：不支持整体拒绝 ====================

    @Test
    void rejectsUnsupportedElements() {
        assertThrows(VisualEditorService.UnsupportedException.class,
                () -> service.serialize("<p><span class=\"x\">?</span></p>", List.of()));
        assertThrows(VisualEditorService.UnsupportedException.class,
                () -> service.serialize("<video src=\"a.mp4\"></video>", List.of()));
        assertThrows(VisualEditorService.UnsupportedException.class,
                () -> service.serialize("<p><a href=\"mailto:a@b.c\">邮件</a></p>", List.of()));
    }

    @Test
    void rejectsInvalidTokenIds() {
        assertThrows(VisualEditorService.UnsupportedException.class,
                () -> service.serialize("<p><span class=\"ve-token\" data-token=\"9\">x</span></p>", List.of()));
    }

    // ==================== 模板对话框：参数发现 / 重建 / 预览 ====================

    @Test
    void templateDialogDiscoversParametersAndRendersPreview() {
        templateMap.put("模板:信息框", "'''{{{标题|}}}'''<br>副标题：{{{副标题|无}}}\n\n第一参数：{{{1|}}}");
        VisualEditorService.TemplateInfo info = service.openTemplate("测试页", "{{信息框|标题=甲|1=乙}}");

        assertEquals("信息框", info.name());
        assertTrue(info.exists());
        assertEquals("模板:信息框", info.title());
        // 发现的参数：位置参数 1 在前，命名参数按源码出现顺序
        assertEquals(List.of("1", "标题", "副标题"),
                info.params().stream().map(VisualEditorService.TemplateParam::name).toList());
        java.util.Map<String, VisualEditorService.TemplateParam> byName = new java.util.HashMap<>();
        info.params().forEach(param -> byName.put(param.name(), param));
        assertEquals("乙", byName.get("1").value());
        assertTrue(byName.get("1").present());
        assertEquals("甲", byName.get("标题").value());
        assertFalse(byName.get("副标题").present());
        // 预览按当前调用渲染
        assertTrue(info.previewHtml().contains("<b>甲</b>"), info.previewHtml());
        assertTrue(info.previewHtml().contains("乙"), info.previewHtml());
        assertTrue(info.previewHtml().contains("副标题：无"), info.previewHtml());
    }

    @Test
    void templateDialogShowsUnknownTemplateWithoutParams() {
        VisualEditorService.TemplateInfo info = service.openTemplate("测试页", "{{不存在的模板|a=1}}");
        assertFalse(info.exists());
        assertEquals("不存在的模板", info.name());
        assertEquals(List.of(new VisualEditorService.TemplateParam("a", "1", false, true, false)),
                info.params());
    }

    @Test
    void templateBuildRebuildsSourceAndEscapesPipe() {
        templateMap.put("模板:卡片", "{{{名称|}}}");
        VisualEditorService.TemplateInfo info = service.buildTemplate("测试页", "卡片", List.of(
                new VisualEditorService.TemplateParam("名称", "甲|乙", false, true, false),
                new VisualEditorService.TemplateParam("备注", "", false, false, true),
                new VisualEditorService.TemplateParam("1", "位置", true, true, false)));
        // 位置参数在前；空值参数不写入；值里的 | 转义成 {{!}}
        assertEquals("{{卡片|位置|名称=甲{{!}}乙}}", info.source());
        assertTrue(info.previewHtml().contains("甲|乙"), info.previewHtml());
    }

    @Test
    void templateInvalidSourceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.openTemplate("测试页", "不是模板"));
        assertThrows(IllegalArgumentException.class, () -> service.openTemplate("测试页", "{{}}"));
    }

    // ==================== 编辑器里的模板块 ====================

    @Test
    void blockTemplateRendersPreviewInEditor() {
        templateMap.put("模板:横幅", "'''横幅：{{{1|}}}'''");
        String source = "正文。\n\n{{横幅|甲}}\n\n尾。\n";
        VisualEditorService.Rendered rendered = service.render(source, "测试页");

        assertTrue(rendered.html().contains("ve-template-block"), rendered.html());
        assertTrue(rendered.html().contains("ve-template-render"), rendered.html());
        assertTrue(rendered.html().contains("横幅：甲"), rendered.html());
        // 预览是生成内容：整块按原始源码回写
        assertEquals(source, service.serialize(rendered.html(), rendered.tokens()));
    }

    @Test
    void inlineTemplateStaysChipWithoutPreviewBlock() {
        templateMap.put("模板:小", "x");
        String source = "前{{小}}后。\n";
        VisualEditorService.Rendered rendered = service.render(source, "测试页");

        assertTrue(rendered.html().contains("ve-template-inline"), rendered.html());
        assertFalse(rendered.html().contains("ve-template-block"), rendered.html());
        assertEquals(source, service.serialize(rendered.html(), rendered.tokens()));
    }

    @Test
    void blockTemplateWithPreviewDomSerializesFromToken() {
        // 模拟前端回传：块级模板的预览 DOM 原样带回（内部结构不会混进源码）
        String html = "<p>前</p>"
                + "<div class=\"ve-token ve-template ve-template-block\" data-token=\"0\" contenteditable=\"false\">"
                + "<div class=\"ve-template-badge\">模板：甲</div>"
                + "<div class=\"ve-template-render\"><p><b>甲</b></p></div></div>";
        assertEquals("前\n\n{{甲}}\n", service.serialize(html, List.of("{{甲}}")));
    }

    // ==================== 画廊：预览 / 图片列表 / 属性 ====================

    @Test
    void galleryRendersPreviewBlockAndRoundTrips() {
        String source = "<gallery widths=\"100\">\n文件:甲.png|说明\n文件:乙.png\n</gallery>\n";
        VisualEditorService.Rendered rendered = service.render(source, "测试页");

        assertTrue(rendered.html().contains("ve-gallery-block"), rendered.html());
        assertTrue(rendered.html().contains("ve-gallery-render"), rendered.html());
        assertTrue(rendered.html().contains("gallery"), rendered.html());
        assertTrue(rendered.html().contains("画廊（2 张图片）"), rendered.html());
        assertEquals(source, service.serialize(rendered.html(), rendered.tokens()));
    }

    @Test
    void galleryDialogParsesItemsAndOptions() {
        VisualEditorService.GalleryInfo info = service.openGallery("测试页",
                "<gallery widths=\"100\" mode=\"nolines\" class=\"test\">\n"
                        + "文件:甲.png|说明一\n乙.png|说明二\n</gallery>");

        assertEquals(List.of("widths", "mode", "class"),
                info.attributes().stream().map(VisualEditorService.GalleryAttribute::name).toList());
        assertEquals(List.of("文件:甲.png", "乙.png"),
                info.items().stream().map(VisualEditorService.GalleryItem::file).toList());
        assertEquals("说明一", info.items().get(0).caption());
        assertEquals("画廊（2 张图片）", info.label());
        assertTrue(info.previewHtml().contains("gallery"), info.previewHtml());
    }

    @Test
    void galleryBuildRebuildsSourceAndPreview() {
        VisualEditorService.GalleryInfo info = service.buildGallery("测试页",
                List.of(new VisualEditorService.GalleryAttribute("widths", "140"),
                        new VisualEditorService.GalleryAttribute("mode", "packed")),
                List.of(new VisualEditorService.GalleryItem("文件:甲.png", "说明"),
                        new VisualEditorService.GalleryItem("乙.png", "")));

        assertEquals("<gallery widths=\"140\" mode=\"packed\">\n"
                + "文件:甲.png|说明\n乙.png\n</gallery>", info.source());
        assertEquals("画廊（2 张图片）", info.label());
        assertTrue(info.previewHtml().contains("gallery"), info.previewHtml());
    }

    @Test
    void galleryInvalidSourceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.openGallery("测试页", "不是画廊"));
        assertThrows(IllegalArgumentException.class,
                () -> service.openGallery("测试页", "<gallery>没有结束标签"));
    }
}
