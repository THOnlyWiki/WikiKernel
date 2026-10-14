package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lua 扩展（mw.html / mw.loadData / mw.site / mw.message / Lua 5.1 shim / 模块依赖追踪）：
 * <ul>
 *     <li>{@code mw.html}：构建器输出 HTML（属性 / 子标签 / tostring）；</li>
 *     <li>{@code mw.loadData}：数据模块（校验 + 缓存）；<b>依赖追踪</b>——数据模块被编辑后，
 *     引用它的模块（含链式）下次渲染即拿到新数据；</li>
 *     <li>{@code mw.site}：站点名 / 版本 / 命名空间 / 统计；</li>
 *     <li>{@code mw.message}：界面消息与原始消息（$1 占位）；</li>
 *     <li>Lua 5.1 兼容层：{@code unpack} / {@code table.getn} / {@code table.maxn}。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class LuaExtensionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    private String render(String title, String content) throws Exception {
        wikiPageService.editPage(null, title, content);
        return mockMvc.perform(get("/wiki/{title}", title))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ==================== mw.html ====================

    @Test
    void htmlBuilderRendersAttributesChildrenAndWikitext() throws Exception {
        wikiPageService.editPage(null, "模块:LuaHtml演示", """
                local p = {}

                function p.main()
                    local root = mw.html.create('div')
                        :addClass('wiki-warning')
                        :attr('id', 'demo-box')
                        :css('color', '#36c')

                    root:tag('b'):wikitext('粗体标题'):done()
                        :tag('table')
                            :addClass('wikitable')
                            :tag('tr')
                                :tag('th'):wikitext('表头一'):done()
                                :tag('th'):wikitext('表头二'):done()
                            :done()
                            :tag('tr')
                                :tag('td'):wikitext('单元格'):done()
                                :tag('td'):attr('title', '带"引号"'):wikitext('数值'):done()
                            :done()
                        :allDone()

                    local br = mw.html.create('br')
                    return tostring(root) .. tostring(br)
                end

                return p
                """);
        String html = render("LuaHtml展示页", "{{#invoke:LuaHtml演示|main}}");

        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("class=\"wiki-warning\"") && html.contains("id=\"demo-box\""), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("color:#36c"), html);
        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("<b>粗体标题</b>") && html.contains("class=\"wikitable\""), html);
        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("<th>表头一</th>") && html.contains("单元格"), html);
        // 属性值里的引号被转义（经 HTML 白名单过滤后 & 会被再转义一次）
        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("title=\"带&amp;quot;引号&amp;quot;\""), html);
        // 空元素自闭合（渲染器的白名单会规范成 <br>）
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<br>"), html);
    }

    // ==================== mw.loadData + 模块依赖追踪 ====================

    @Test
    void loadDataReadsTableAndTracksRevisionDependency() throws Exception {
        wikiPageService.editPage(null, "模块:Lua数据", """
                return {
                    ['数值'] = 42,
                    ['列表'] = { '甲', '乙' },
                    ['嵌套'] = { ['深层'] = '深层值' },
                }
                """);
        wikiPageService.editPage(null, "模块:Lua数据用户", """
                local p = {}

                function p.main()
                    local data = mw.loadData('Lua数据')
                    return data['数值'] .. '/' .. data['列表'][2] .. '/' .. data['嵌套']['深层']
                end

                return p
                """);
        String html1 = render("Lua数据页", "{{#invoke:Lua数据用户|main}}");
        org.junit.jupiter.api.Assertions.assertTrue(html1.contains("42/乙/深层值"), html1);

        // 编辑数据模块：依赖追踪应让下次渲染拿到新数据
        wikiPageService.editPage(null, "模块:Lua数据", """
                return {
                    ['数值'] = 99,
                    ['列表'] = { '甲', '丙' },
                    ['嵌套'] = { ['深层'] = '新的深层值' },
                }
                """);
        String html2 = render("Lua数据页", "{{#invoke:Lua数据用户|main}}");
        org.junit.jupiter.api.Assertions.assertTrue(html2.contains("99/丙/新的深层值"), html2);
    }

    @Test
    void chainedLoadDataTracksNestedDependency() throws Exception {
        wikiPageService.editPage(null, "模块:Lua链数据", """
                return { ['值'] = 7 }
                """);
        wikiPageService.editPage(null, "模块:Lua链包装", """
                local base = mw.loadData('模块:Lua链数据')
                return { ['值'] = base['值'], ['来源'] = '包装' }
                """);
        wikiPageService.editPage(null, "模块:Lua链用户", """
                local p = {}

                function p.main()
                    local data = mw.loadData('Lua链包装')
                    return data['值'] .. '-' .. data['来源']
                end

                return p
                """);
        String html1 = render("Lua链数据页", "{{#invoke:Lua链用户|main}}");
        org.junit.jupiter.api.Assertions.assertTrue(html1.contains("7-包装"), html1);

        // 改内层数据：外层包装的缓存依赖里记着内层修订号 → 自动重载
        wikiPageService.editPage(null, "模块:Lua链数据", "return { ['值'] = 8 }");
        String html2 = render("Lua链数据页", "{{#invoke:Lua链用户|main}}");
        org.junit.jupiter.api.Assertions.assertTrue(html2.contains("8-包装"), html2);
    }

    @Test
    void loadDataValidatesFunctionsAndCycles() throws Exception {
        wikiPageService.editPage(null, "模块:Lua坏数据函数", """
                return { fn = function() return 1 end }
                """);
        wikiPageService.editPage(null, "模块:Lua坏数据用户", """
                local p = {}

                function p.main()
                    mw.loadData('Lua坏数据函数')
                    return '不应该到这里'
                end

                return p
                """);
        String html1 = render("Lua坏数据页一", "{{#invoke:Lua坏数据用户|main}}");
        org.junit.jupiter.api.Assertions.assertTrue(html1.contains("数据模块只能包含"), html1);

        wikiPageService.editPage(null, "模块:Lua坏数据循环", """
                local t = {}
                t['自己'] = t
                return t
                """);
        wikiPageService.editPage(null, "模块:Lua坏数据循环用户", """
                local p = {}

                function p.main()
                    mw.loadData('Lua坏数据循环')
                    return '不应该到这里'
                end

                return p
                """);
        String html2 = render("Lua坏数据页二", "{{#invoke:Lua坏数据循环用户|main}}");
        org.junit.jupiter.api.Assertions.assertTrue(html2.contains("循环引用"), html2);
    }

    // ==================== mw.site / mw.message / 5.1 shim ====================

    @Test
    void siteInfoAndMessageLibraryWork() throws Exception {
        wikiPageService.editPage(null, "模块:Lua站内演示", """
                local p = {}

                function p.main()
                    local site = mw.site
                    local ns = site.namespaces[10].name
                    local stats = site.stats
                    local message = mw.message.new('toolbar.bold'):text()
                    local raw = mw.message.newRawMessage('你好，$1！', '世界'):text()
                    local missing = mw.message.new('不存在的消息键')
                    return string.format('%s|%s|%s|%d|%d|%s|%s|%s|%s',
                        site.siteName, site.currentVersion, ns, stats.pages, stats.users,
                        message, raw, tostring(missing:exists()), missing:text())
                end

                return p
                """);
        String html = render("Lua站内演示页", "{{#invoke:Lua站内演示|main}}");

        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("WikiKernel|0.0.1-SNAPSHOT|模板|")
                        || html.contains("WikiKernel|0.0.1-SNAPSHOT|Template|"), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("|粗体|") || html.contains("|Bold|"), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("你好，世界！"), html);
        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("|false|&lt;不存在的消息键&gt;"), html);
    }

    @Test
    void lua51ShimProvidesUnpackAndTableHelpers() throws Exception {
        wikiPageService.editPage(null, "模块:Lua51演示", """
                local p = {}

                function p.main()
                    local list = { '甲', '乙', '丙' }
                    local first, second = unpack(list)
                    local maxn = table.maxn({ [5] = 'x', [2] = 'y' })
                    return first .. second .. '/' .. table.getn(list) .. '/' .. maxn
                end

                return p
                """);
        String html = render("Lua51演示页", "{{#invoke:Lua51演示|main}}");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("甲乙/3/5"), html);
    }

    // ==================== 复杂页面（解析器类测试约定） ====================

    @Test
    void complexPageCombinesAllLuaExtensions() throws Exception {
        wikiPageService.editPage(null, "模块:综合演示数据", """
                return {
                    ['站点'] = { ['名称'] = 'WikiKernel', ['标语'] = "中文示例'站" },
                    ['指标'] = { ['页面'] = 128, ['编辑'] = 1024 },
                }
                """);
        wikiPageService.editPage(null, "模块:综合演示卡片", """
                local p = {}

                function p.main(frame)
                    local data = mw.loadData('综合演示数据')
                    local site = mw.site
                    local card = mw.html.create('div')
                        :addClass('wiki-warning')
                        :attr('id', 'complex-card')
                    card:tag('b'):wikitext(data['站点']['名称'] .. '：' .. frame.args[1]):done()
                    local table_ = card:tag('table'):addClass('wikitable')
                    for _, row in ipairs({ { '指标', '数值' }, { '页面', data['指标']['页面'] }, { '编辑', data['指标']['编辑'] } }) do
                        local tr = table_:tag('tr')
                        tr:tag('td'):wikitext(row[1]):done()
                        tr:tag('td'):wikitext(tostring(row[2])):done()
                    end
                    table_:allDone()
                    local version = mw.message.newRawMessage('版本 $1，共 $2 次编辑', site.currentVersion, data['指标']['编辑']):text()
                    return tostring(card) .. '<!-- ' .. version .. ' -->'
                end

                return p
                """);
        String html = render("Lua综合演示页", """
                == 综合演示 ==

                普通段落与 '''粗体'''。

                {{#invoke:综合演示卡片|main|信息卡}}

                * 列表一
                * 列表二

                ''斜体''与[[首页|内链]]。
                """);

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"complex-card\""), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<b>WikiKernel：信息卡</b>"), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("class=\"wikitable\""), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("128") && html.contains("1024"), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<b>粗体</b>"), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<i>斜体</i>") && html.contains("内链"), html);
        // 注释不应出现在页面上（消息文本被注释包裹，不会可见）
        org.junit.jupiter.api.Assertions.assertTrue(!html.contains("共 1024 次编辑"), html);
    }
}
