package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lua 模块（Scribunto MVP）：#invoke、frame 参数、沙盒、错误、超时、缓存与编辑器。 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class LuaModuleTest {

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

    private String invoke(String pageTitle, String moduleTitle, String moduleSource,
                          String wikitext) throws Exception {
        wikiPageService.editPage(null, moduleTitle, moduleSource);
        return render(pageTitle, wikitext);
    }

    // ==================== 基本调用 ====================

    @Test
    void invokeRunsModuleAndResultIsParsedAsWikitext() throws Exception {
        String html = invoke("Lua调用页", "模块:Lua问候", """
                local p = {}
                function p.hello(frame)
                    return "你好，" .. frame.args[1] .. "！'''粗体'''"
                end
                return p
                """, "{{#invoke:Lua问候|hello|世界}}");

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("你好，世界！"), html);
        // 返回文本继续按 Wikitext 解析（仿 Scribunto）
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<b>粗体</b>"), html);
    }

    @Test
    void positionalArgsKeepWhitespaceAndNamedArgsAreTrimmed() throws Exception {
        String html = invoke("Lua参数页", "模块:Lua参数", """
                local p = {}
                function p.show(frame)
                    return "[" .. frame.args[1] .. "](" .. frame.args["名字"] .. ")"
                end
                return p
                """, "{{#invoke:Lua参数|show| 甲 |名字= 乙 }}");

        // 位置参数保留空白，命名参数去首尾空白（仿 Scribunto）
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("[ 甲 ](乙)"), html);
    }

    @Test
    void parentFrameExposesTemplateArgs() throws Exception {
        wikiPageService.editPage(null, "模块:Lua父参数", """
                local p = {}
                function p.main(frame)
                    local parent = frame:getParent()
                    local fromParent = parent and parent.args["甲"] or "none"
                    local parentTitle = parent and parent:getTitle() or "none"
                    return parentTitle .. "/" .. fromParent .. "/" .. frame.args["乙"]
                end
                return p
                """);
        wikiPageService.editPage(null, "模板:Lua父框架", "{{#invoke:Lua父参数|main|乙={{{乙|}}}}}");
        String html = render("Lua父参数页", "{{Lua父框架|甲=值甲|乙=值乙}}");

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("模板:Lua父框架/值甲/值乙"), html);
    }

    // ==================== mw 库 ====================

    @Test
    void mwTextTitleAndUriWork() throws Exception {
        String html = invoke("Lua库页", "模块:Lua库", """
                local p = {}
                function p.main(frame)
                    local text = "  <b>粗</b>体  "
                    local title = mw.title.new("模块:Lua库")
                    return mw.text.trim(mw.text.plain(text))
                        .. "|" .. title.nsText .. "|" .. title.text
                        .. "|" .. mw.uri.encode("a b/中")
                        .. "|" .. mw.uri.decode("a%20b%2F%E4%B8%AD")
                        .. "|" .. mw.getCurrentFrame().args[1]
                end
                return p
                """, "{{#invoke:Lua库|main|位置}}");

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("粗体|模块|Lua库|a%20b%2F%E4%B8%AD|a b/中|位置"), html);
    }

    @Test
    void mwTextFormatUsesLuaStringFormat() throws Exception {
        String html = invoke("Lua格式化页", "模块:Lua格式化", """
                local p = {}
                function p.main(frame)
                    return mw.text.format("%s=%d", "甲", 3)
                end
                return p
                """, "{{#invoke:Lua格式化|main}}");

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("甲=3"), html);
    }

    // ==================== 错误处理 ====================

    @Test
    void missingModuleAndFunctionShowErrorsWithoutBreakingPage() throws Exception {
        String html = render("Lua缺模块页", "前{{#invoke:不存在的模块|f}}后");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("找不到模块"), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("前") && html.contains("后"), html);

        wikiPageService.editPage(null, "模块:Lua无函数", "return {}");
        String html2 = render("Lua缺函数页", "{{#invoke:Lua无函数|f}}");
        org.junit.jupiter.api.Assertions.assertTrue(html2.contains("没有函数"), html2);
    }

    @Test
    void scriptErrorsAreReported() throws Exception {
        String html = invoke("Lua报错页", "模块:Lua报错", """
                local p = {}
                function p.main()
                    error("炸了")
                end
                return p
                """, "{{#invoke:Lua报错|main}}");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("脚本错误"), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("炸了"), html);
    }

    @Test
    void disallowedReturnTypeIsReported() throws Exception {
        String html = invoke("Lua返回页", "模块:Lua返回", """
                local p = {}
                function p.main()
                    return true
                end
                return p
                """, "{{#invoke:Lua返回|main}}");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("不允许的类型"), html);
    }

    // ==================== 沙盒与超时 ====================

    @Test
    void sandboxBlocksDangerousGlobals() throws Exception {
        String html = invoke("Lua沙盒页", "模块:Lua沙盒", """
                local p = {}
                function p.main()
                    return type(os) .. type(io) .. type(require) .. type(dofile)
                        .. type(loadfile) .. type(print) .. type(luajava)
                end
                return p
                """, "{{#invoke:Lua沙盒|main}}");

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("nilnilfunctionnilnilnilnil"), html);
    }

    @Test
    void infiniteLoopIsAbortedWithTimeoutError() throws Exception {
        long started = System.currentTimeMillis();
        String html = invoke("Lua死循环页", "模块:Lua死循环", """
                local p = {}
                function p.main()
                    local count = 0
                    while true do
                        pcall(function() count = count + 1 end)
                    end
                    return count
                end
                return p
                """, "{{#invoke:Lua死循环|main}}");
        long elapsed = System.currentTimeMillis() - started;

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("运行超时"), html);
        org.junit.jupiter.api.Assertions.assertTrue(elapsed < 10_000, "耗时 " + elapsed + "ms");
    }

    // ==================== 缓存与循环 ====================

    @Test
    void moduleCacheFollowsRevision() throws Exception {
        String html1 = invoke("Lua缓存页", "模块:Lua缓存", """
                local p = {}
                function p.v() return "版本1" end
                return p
                """, "{{#invoke:Lua缓存|v}}");
        org.junit.jupiter.api.Assertions.assertTrue(html1.contains("版本1"), html1);

        wikiPageService.editPage(null, "模块:Lua缓存", """
                local p = {}
                function p.v() return "版本2" end
                return p
                """);
        String html2 = render("Lua缓存页", "{{#invoke:Lua缓存|v}}");
        org.junit.jupiter.api.Assertions.assertTrue(html2.contains("版本2"), html2);
    }

    @Test
    void invokeLoopIsDetected() throws Exception {
        String html = invoke("Lua循环页", "模块:Lua循环", """
                local p = {}
                function p.main()
                    return "{{#invoke:Lua循环|main}}"
                end
                return p
                """, "{{#invoke:Lua循环|main}}");

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("循环"), html);
    }

    // ==================== 模块页与编辑器 ====================

    @Test
    void modulePagesRenderAndEditAsLuaCode() throws Exception {
        wikiPageService.editPage(null, "模块:Lua展示", """
                local p = {}
                function p.main() return "ok" end
                return p
                """);

        mockMvc.perform(get("/wiki/{title}", "模块:Lua展示"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-language=\"lua\"")))
                .andExpect(content().string(containsString("function p.main")));

        mockMvc.perform(get("/wiki/{title}", "模块:Lua展示").param("action", "edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-language=\"lua\"")))
                .andExpect(content().string(containsString("mode-lua.min.js")))
                // 模块页是代码页：没有可视化编辑面板
                .andExpect(content().string(not(containsString("id=\"visual-editor\""))));
    }

    // ==================== 复杂模块 + 复杂页面 ====================

    /**
     * 解析器类测试约定：测的是「真实页面的组合效果」，不写单函数 + 单行调用的小页面。
     * 本用例：多函数模块（局部辅助函数 / 迭代 / 字符串模式 / pcall）+ 模板包装（父框架参数）
     * + 表格与列表生成 + 与标题、粗斜体、内链混排的页面。
     */
    @Test
    void complexModuleAndMixedPageRenderCorrectly() throws Exception {
        wikiPageService.editPage(null, "模块:Lua综合演示", """
                -- 综合演示：局部辅助函数 + 迭代 + 字符串模式 + mw 库 + 表格/列表生成
                local p = {}

                local function numbers(text)
                    local list = {}
                    for piece in string.gmatch(text or "", "[^,]+") do
                        local value = tonumber(mw.text.trim(piece))
                        if value then list[#list + 1] = value end
                    end
                    return list
                end

                local function group(value)
                    local text = string.format("%.2f", value)
                    local int, frac = text:match("^(%-?%d+)%.(%d+)$")
                    int = int:reverse():gsub("(%d%d%d)", "%1,")
                    int = int:reverse():gsub("^,", "")
                    return int .. "." .. frac
                end

                function p.stats(frame)
                    local list = numbers(frame.args[1])
                    if #list == 0 then return "'''没有数据'''" end
                    local sum, size = 0, #list
                    local min, max = list[1], list[1]
                    for _, value in ipairs(list) do
                        sum = sum + value
                        if value < min then min = value end
                        if value > max then max = value end
                    end
                    return string.format("共 %d 个：总和 %s，平均 %s，最小 %s，最大 %s",
                        size, group(sum), group(sum / size), group(min), group(max))
                end

                function p.rowTable(frame)
                    local lines = { '{| class="wikitable"', '! 名称 !! 数值' }
                    for _, row in ipairs(frame.args) do
                        local name, value = string.match(row, "^([^;]+);(.+)$")
                        if name then
                            lines[#lines + 1] = '|-'
                            lines[#lines + 1] = '| ' .. mw.text.trim(name)
                            lines[#lines + 1] = '| ' .. mw.text.trim(value)
                        end
                    end
                    lines[#lines + 1] = '|}'
                    return table.concat(lines, "\\n")
                end

                function p.squares(frame)
                    local count = tonumber(frame.args[1]) or 10
                    local items = {}
                    for i = 1, count do
                        items[#items + 1] = '* ' .. tostring(i) .. '的平方是 ' .. tostring(i * i)
                    end
                    return table.concat(items, "\\n")
                end

                function p.safe(frame)
                    local divisor = tonumber(frame.args[1])
                    if divisor == nil then return "参数不是数字" end
                    local ok, value = pcall(function()
                        if divisor == 0 then error("除数为零") end
                        return 100 / divisor
                    end)
                    if ok then return string.format("100 除以 %d 得 %.1f", divisor, value) end
                    return "被捕获：" .. tostring(value)
                end

                function p.card(frame)
                    local args = frame:getParent() and frame:getParent().args or frame.args
                    local title = args["标题"] or "未命名"
                    local data = args["数据"] or "（空）"
                    local module = mw.title.new("模块:Lua综合演示")
                    return string.format("'''%s'''：%s｜模块=%s.%s｜当前=%s",
                        title, data, module.nsText, module.text, mw.title.getCurrentTitle().text)
                end

                return p
                """);
        wikiPageService.editPage(null, "模板:Lua综合卡片",
                "{{#invoke:Lua综合演示|card|标题={{{标题|}}}|数据={{{数据|}}}}}");

        String html = render("Lua综合演示页", """
                == Lua 综合演示 ==

                信息卡：{{Lua综合卡片|标题=测试卡片|数据=42}}

                统计：{{#invoke:Lua综合演示|stats|3, 1.5, 7, 9, 2.5}}

                {{#invoke:Lua综合演示|rowTable|甲;10|乙;20|丙;30}}

                === 平方列表 ===

                {{#invoke:Lua综合演示|squares|5}}

                计算：{{#invoke:Lua综合演示|safe|4}}；{{#invoke:Lua综合演示|safe|0}}

                <!-- 注释不应出现在页面上 -->

                ''斜体''与[[首页|内链]]。
                """);

        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("<b>测试卡片</b>：42｜模块=模块.Lua综合演示｜当前=Lua综合演示页"), html);
        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("共 5 个：总和 23.00，平均 4.60，最小 1.50，最大 9.00"), html);
        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("wikitable") && html.contains("甲") && html.contains("丙") && html.contains("30"), html);
        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("3的平方是 9") && html.contains("5的平方是 25") && html.contains("<li>"), html);
        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("100 除以 4 得 25.0")
                        && html.contains("被捕获：") && html.contains("除数为零"), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<i>斜体</i>"), html);
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("内链"), html);
        org.junit.jupiter.api.Assertions.assertTrue(!html.contains("注释不应出现在页面上"), html);
        org.junit.jupiter.api.Assertions.assertTrue(!html.contains("{{{"), html);
    }
}
