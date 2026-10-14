package cc.thonly.wikikernel.plugin.easytimeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EasyTimeline（{@code <timeline>}）子集渲染器单元测试：用一份尽量复杂的定义
 * （周期 / 主次刻度 / 颜色与图例 / 柱条 + barset + 端点 $ / 点标记 / TextData）覆盖主要分支。
 */
class TimelineRendererTest {

    /** 复杂组合定义：把能想到的常用命令都放进去。 */
    private static final String COMPLEX_TIMELINE = """
            ImageSize = width:640 height:240 barincrement:26
            PlotArea = left:80 right:120 top:20 bottom:50
            AlignBars = late
            DateFormat = yyyy
            Period = from:1990 till:2015
            TimeAxis = orientation:horizontal
            ScaleMajor = unit:year increment:5 start:1990 gridcolor:gray
            ScaleMinor = unit:year increment:1 start:1990 gridcolor:gray(0.85)
            Colors =
              id:bg value:white
              id:china value:red legend:中国
              id:india value:rgb(0,0,1) legend:印度
            BackgroundColors = canvas:white bars:gray(0.92)
            PlotData =
              width:18 align:center textcolor:black fontsize:M
              bar:中国 from:1990 till:2010 text:"中国" color:china
              bar:印度 from:1992 till:$ text:"印度" color:india
              barset:亚洲
              bar:日本 from:1995 till:2005 text:"日本" color:india
              at:2010 text:"标记" color:china
            TextData =
              pos:(80,20) text:"国内生产总值" color:black fontsize:L
            Legend = orientation:horizontal columns:2
            """;

    @Test
    void rendersComplexTimelineAsInlineSvg() {
        String html = TimelineRenderer.render(COMPLEX_TIMELINE);
        assertNotNull(html);
        // 外层与尺寸
        assertTrue(html.contains("<div class=\"mw-timeline\">"), html);
        assertTrue(html.contains("class=\"mw-timeline-svg\""), html);
        assertTrue(html.contains("viewBox=\"0 0 640 240\""), html);
        // 柱条颜色（含 rgb(0,0,1) → #0000ff 的归一化）
        assertTrue(html.contains("fill=\"red\""), html);
        assertTrue(html.contains("fill=\"#0000ff\""), html);
        // 背景（gray(0.92) → #ebebeb）
        assertTrue(html.contains("fill=\"#ebebeb\""), html);
        // 柱条文字、barset 标签、TextData
        assertTrue(html.contains("中国"), html);
        assertTrue(html.contains("亚洲"), html);
        assertTrue(html.contains("国内生产总值"), html);
        // 主刻度标签（1990 / 1995 / …）
        assertTrue(html.contains(">1990</text>"), html);
        assertTrue(html.contains(">1995</text>"), html);
        // 图例
        assertTrue(html.contains("mw-timeline-legend"), html);
        assertTrue(html.contains("印度"), html);
        // 不引入脚本（服务端只产出 SVG，插到正文里也是可信静态块）
        assertFalse(html.toLowerCase().contains("<script"), html);
    }

    @Test
    void textIsEscapedInsideSvg() {
        String html = TimelineRenderer.render("""
                ImageSize = width:320 height:160
                Period = from:2000 till:2010
                PlotData =
                  bar:<b>x</b> from:2000 till:2005
                TextData =
                  pos:(10,10) text:"<script>alert(1)</script>"
                """);
        assertNotNull(html);
        assertFalse(html.contains("<script>alert(1)</script>"), html);
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html);
        assertTrue(html.contains("&lt;b&gt;x&lt;/b&gt;"), html);
    }

    @Test
    void emptyOrBrokenDefinitionReturnsNull() {
        assertNull(TimelineRenderer.render(""));
        assertNull(TimelineRenderer.render("   \n  "));
        // Period 反了（till < from）→ 无法确定坐标 → null（调用方显示错误提示）
        assertNull(TimelineRenderer.render("Period = from:2010 till:2000"));
    }
}
