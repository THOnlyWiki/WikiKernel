package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.SiteSettingsService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 复杂组合页面 + 已迁出插件的解析器扩展：{@code <timeline>}（EasyTimeline）、
 * {@code <graph>}（Graph）、{@code <math>} / {@code <chem>}（Math）现在都由插件提供，
 * **core 未加载插件时按原文转义显示**，同页其它语法（诗体 / 表格 / 代码 / 链接）不受影响。
 * <p>
 * 图形迁移后的行为：
 * <ul>
 *     <li>{@code <graph>} 不再是 core 的内置标签，{@code 图表:} 命名空间按普通页面渲染；</li>
 *     <li>Vega / Vega-Lite / vega-embed 仍由 core 内置（{@code /vendor/vega/}），供插件 graph 加载；</li>
 *     <li>功能开关 {@code features.graph} 留在站点设置里（插件经 SiteConfig 读取）。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class GraphTimelineTest {

    /** 复杂组合页面：把 timeline / graph / math / chem / 代码 / 诗体 / 表格 / 链接 放在同一页。 */
    private static final String COMPLEX_PAGE = """
            == 时间轴 ==

            <timeline>
            ImageSize = width:520 height:200
            Period = from:1900 till:2000
            ScaleMajor = unit:year increment:20 start:1900
            PlotData =
              bar:甲 from:1900 till:1950 text:"甲" color:red
              bar:乙 from:1950 till:2000 text:"乙" color:blue
            </timeline>

            == 图形 ==

            <graph title="柱状图">
            {
              "$schema": "https://vega.github.io/schema/vega-lite/v5.json",
              "data": {"values": [{"x": "A", "y": 3}, {"x": "B", "y": 5}]},
              "mark": "bar",
              "encoding": {"x": {"field": "x", "type": "nominal"}, "y": {"field": "y", "type": "quantitative"}}
            }
            </graph>

            == 数学与化学 ==

            行内 <math>x^2</math>；块级：

            <math display="block">\\int_0^1 x^2 dx</math>

            <chem>2H_2 + O_2 -> 2H_2O</chem>

            == 代码与诗 ==

            <syntaxhighlight lang="java">int a = 1;</syntaxhighlight>

            <poem>
            床前明月光
            疑是地上霜
            </poem>

            == 表格与链接 ==

            {| class="wikitable"
            |-
            ! 列 !! 值
            |-
            | [[首页]] || 1
            |}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private FeatureService featureService;
    @Autowired
    private SiteSettingsService siteSettingsService;


    @Autowired
    private PermissionService permissionService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void complexPageRendersGraphAndFriends() throws Exception {
        String title = "图形与时间轴组合演示页" + System.nanoTime();
        wikiPageService.editPage(null, title, COMPLEX_PAGE);
        try {
            siteSettingsService.set("features.timeline", Boolean.toString(true));
            siteSettingsService.set("features.graph", Boolean.toString(true));
            // 同页也会用到 Math 渲染（Math 开关可能被其它测试用例改动，这里显式打开）
            siteSettingsService.set("features.math", Boolean.toString(true));
            mockMvc.perform(get("/wiki/{title}", title))
                    .andExpect(status().isOk())
                    // 时间轴：已迁到插件 easy-timeline，core 未加载插件时按原文转义显示
                    .andExpect(content().string(containsString("&lt;timeline&gt;")))
                    .andExpect(content().string(not(containsString("mw-timeline-svg"))))
                    // 图形：已迁到插件 graph，core 未加载插件时按原文转义显示（即使开关是开的）
                    .andExpect(content().string(containsString("&lt;graph")))
                    .andExpect(content().string(not(containsString("class=\"mw-graph\""))))
                    .andExpect(content().string(not(containsString("/plugins/graph/graph.js"))))
                    // 同页其它扩展（确认组合页面没互相打断；Math 已迁到插件 math，core 按原文转义）
                    .andExpect(content().string(containsString("&lt;math&gt;")))
                    .andExpect(content().string(not(containsString("class=\"mw-math\""))))
                    .andExpect(content().string(containsString("class=\"poem\"")))
                    .andExpect(content().string(containsString("class=\"wikitable\"")));
        } finally {
            siteSettingsService.set("features.timeline", Boolean.toString(true));
            siteSettingsService.set("features.graph", Boolean.toString(true));
            siteSettingsService.set("features.math", Boolean.toString(true));
        }
    }

    @Test
    void timelineTagIsUnknownToCoreWithoutPlugin() throws Exception {
        String title = "时间轴关闭页" + System.nanoTime();
        wikiPageService.editPage(null, title,
                "<timeline>Period = from:2000 till:2010</timeline>");
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;timeline&gt;")))
                .andExpect(content().string(not(containsString("mw-timeline-svg"))));
    }

    @Test
    void graphTagIsUnknownToCoreWithoutPlugin() throws Exception {
        String title = "图形关闭页" + System.nanoTime();
        wikiPageService.editPage(null, title, "<graph>{\"mark\":\"bar\"}</graph>");
        try {
            siteSettingsService.set("features.graph", Boolean.toString(false));
            mockMvc.perform(get("/wiki/{title}", title))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("&lt;graph&gt;")))
                    .andExpect(content().string(not(containsString("class=\"mw-graph\""))))
                    .andExpect(content().string(not(containsString("/vendor/vega/"))));
        } finally {
            siteSettingsService.set("features.graph", Boolean.toString(true));
        }
        // 开关打开也一样：core 不再内置 <graph>，只有插件加载后才渲染
        siteSettingsService.set("features.graph", Boolean.toString(true));
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;graph&gt;")))
                .andExpect(content().string(not(containsString("class=\"mw-graph\""))));
    }

    @Test
    void graphNamespacePageFallsBackToPlainPage() throws Exception {
        String title = "图表:Vega测试图" + System.nanoTime();
        wikiPageService.editPage(null, title, """
                {
                  "$schema": "https://vega.github.io/schema/vega-lite/v5.json",
                  "data": {"values": [{"x": "A", "y": 1}, {"x": "B", "y": 2}]},
                  "mark": "line",
                  "encoding": {"x": {"field": "x", "type": "ordinal"}, "y": {"field": "y", "type": "quantitative"}}
                }
                """);
        mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                // 插件未加载：整页图不再由 core 生成，正文按普通页面渲染（规范原文可见）
                .andExpect(content().string(not(containsString("class=\"mw-graph\""))))
                .andExpect(content().string(containsString("vega-lite/v5.json")));
    }

    @Test
    void settingsFormNoLongerKnowsPluginSwitches() throws Exception {
        // 插件开关现在由插件在描述文件里声明（plugin.featureKey），core 的设置页不再内置这些复选框；
        // core 未加载插件时表单里没有它们（加载插件后由 core 自动渲染，见 PluginSettingsSectionTest）
        WikiUser admin = new WikiUser();
        admin.setUsername("图形时间轴设置管理员" + System.nanoTime());
        admin.setPassword("x");
        admin.setEmail("");
        wikiUserMapper.insert(admin);
        permissionService.replaceFor(admin.getId(), List.of(PermissionService.GROUP_ADMIN));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserSession.ATTRIBUTE, admin.toTransferCopy());
        mockMvc.perform(get("/wiki/{title}", "特殊:权限配置").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"site-settings-form\"")))
                .andExpect(content().string(not(containsString("name=\"timeline\""))))
                .andExpect(content().string(not(containsString("name=\"graph\""))))
                .andExpect(content().string(not(containsString("时间轴（EasyTimeline）"))))
                .andExpect(content().string(not(containsString("图形（Graph / Vega）"))));
    }

    @Test
    void vegaAssetsAreServedLocally() throws Exception {
        // Vega 前端库仍由 core 内置（插件 graph 通过 /vendor/vega/ 加载，不走 CDN）
        mockMvc.perform(get("/vendor/vega/vega.min.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(content().string(containsString("vega")));
        mockMvc.perform(get("/vendor/vega/vega-lite.min.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(content().string(containsString("vegaLite")));
        mockMvc.perform(get("/vendor/vega/vega-embed.min.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(content().string(containsString("vegaEmbed")));
    }
}
