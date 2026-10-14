package cc.thonly.wikikernel.plugin.pagebanner.hook;

import cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes;
import cc.thonly.wikikernel.plugin.pagebanner.service.PageBannerRenderer;
import java.util.List;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.FakeRenderContext;
import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.functionCall;
import static cc.thonly.wikikernel.plugin.pagebanner.PageBannerFakes.tagCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PageBannerDirective}（{@code {{PAGEBANNER:文件名|caption=说明}}}）与
 * {@link ExcludeBannerTag}（{@code <exclude-banner-page />}）：
 * 指令只写页面属性、自身输出空串（从正文剥离）。
 */
class PageBannerDirectiveTest {

    @Test
    void directiveWritesFileAndCaptionProperties() {
        PageBannerDirective directive = new PageBannerDirective();
        FakeRenderContext context = new FakeRenderContext("横幅演示页");

        assertEquals("PAGEBANNER", directive.name());
        assertEquals("", directive.invoke(functionCall(context, "横幅.png|caption=说明文字 带空格")));
        assertEquals("横幅.png", context.properties().get(PageBannerRenderer.FILE_PROPERTY));
        assertEquals("说明文字 带空格", context.properties().get(PageBannerRenderer.CAPTION_PROPERTY));
    }

    @Test
    void captionAcceptsQuotesAndNamedFileParameters() {
        FakeRenderContext quoted = new FakeRenderContext();
        new PageBannerDirective().invoke(functionCall(quoted, "横幅.png|caption=\"山上的日出\""));
        assertEquals("山上的日出", quoted.properties().get(PageBannerRenderer.CAPTION_PROPERTY));

        FakeRenderContext named = new FakeRenderContext();
        new PageBannerDirective().invoke(functionCall(named, "image=甲.png|caption=说明"));
        assertEquals("甲.png", named.properties().get(PageBannerRenderer.FILE_PROPERTY));
        assertEquals("说明", named.properties().get(PageBannerRenderer.CAPTION_PROPERTY));

        // 文件名里的「文件:」前缀原样保留（插件的 FileStore 会剥掉）
        FakeRenderContext prefixed = new FakeRenderContext();
        new PageBannerDirective().invoke(functionCall(prefixed, "文件:乙.png"));
        assertEquals("文件:乙.png", prefixed.properties().get(PageBannerRenderer.FILE_PROPERTY));
    }

    @Test
    void multipleFileArgumentsBecomeACarouselList() {
        // {{PAGEBANNER:甲.png|乙.png}}：core 按 `|` 拆参数，指令用同一个分隔符拼回页面属性
        FakeRenderContext multiple = new FakeRenderContext("轮播页");
        new PageBannerDirective().invoke(functionCall(multiple, "甲.png|乙.png|caption=说明"));
        assertEquals("甲.png|乙.png", multiple.properties().get(PageBannerRenderer.FILE_PROPERTY));
        assertEquals("说明", multiple.properties().get(PageBannerRenderer.CAPTION_PROPERTY));

        // 命名参数（image=）同样可以出现多次；不支持的命名参数（icon=）不会挤进文件名
        FakeRenderContext named = new FakeRenderContext();
        new PageBannerDirective().invoke(functionCall(named, "image=甲.png|image=乙.png|icon=星形.svg"));
        assertEquals("甲.png|乙.png", named.properties().get(PageBannerRenderer.FILE_PROPERTY));
        assertEquals("", named.properties().get(PageBannerRenderer.CAPTION_PROPERTY));
    }

    @Test
    void unknownNamedParametersAreIgnored() {
        // {{PAGEBANNER:}}（不指定图片，只有轮换机制不支持参数）：属性为空串
        FakeRenderContext empty = new FakeRenderContext();
        assertEquals("", new PageBannerDirective().invoke(functionCall(empty, "")));
        assertEquals("", empty.properties().get(PageBannerRenderer.FILE_PROPERTY));
        assertEquals("", empty.properties().get(PageBannerRenderer.CAPTION_PROPERTY));

        // 只写了不支持的命名参数（icon=）：不把它当成文件名，仍从图片池取
        FakeRenderContext icon = new FakeRenderContext();
        new PageBannerDirective().invoke(functionCall(icon, "icon=星形.svg|caption=说明"));
        assertEquals("", icon.properties().get(PageBannerRenderer.FILE_PROPERTY));
        assertEquals("说明", icon.properties().get(PageBannerRenderer.CAPTION_PROPERTY));
    }

    @Test
    void excludeTagIsSelfClosingUnknownBodyAndWritesProperty() {
        ExcludeBannerTag tag = new ExcludeBannerTag();
        FakeRenderContext context = new FakeRenderContext();

        assertEquals("exclude-banner-page", tag.name());
        assertEquals(List.of("exclude-banner-page"), tag.names());
        // 内容不参与模板展开：与迁移前 core 在模板展开前剥离它的行为一致
        assertFalse(tag.expandBody());
        assertEquals("", tag.render(tagCall(context, "")));
        assertEquals("1", context.properties().get(PageBannerRenderer.EXCLUDE_PROPERTY));
        assertTrue(PageBannerRenderer.excluded(context.properties()));
    }
}
