package cc.thonly.wikikernel.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

/**
 * {@code mw.html}：仿 Scribunto 的 HTML 构建器（Lua 实现，语义与官方一致的安全子集）。
 * <ul>
 *     <li>{@code mw.html.create( tagName )}；</li>
 *     <li>构建器：{@code addClass / attr / css / cssText / node / wikitext / newline /
 *     tag(子标签) / done(回父级) / allDone(回顶层)}；{@code tostring(builder)} 输出 HTML；</li>
 *     <li>空元素（br / hr / img …）输出 {@code <br />}；属性值只转义 {@code "}（与 Scribunto 一致，
 *     wikitext / 节点内容原样拼接）；</li>
 *     <li>安全性：输出最终仍会经过渲染器的 HTML 白名单过滤，模块无法借此注入脚本。</li>
 * </ul>
 * 在模块环境编译时执行一次，{@code mw.html} 指向返回的 html 表。
 */
final class LuaHtmlLibrary {

    private LuaHtmlLibrary() {
    }

    private static final String SOURCE = """
            -- mw.html（Scribunto 兼容子集）
            local html = {}

            local HtmlBuilder = {}
            HtmlBuilder.__index = HtmlBuilder

            local voidElements = {
                area = true, base = true, br = true, col = true, command = true, embed = true,
                hr = true, img = true, input = true, keygen = true, link = true, meta = true,
                param = true, source = true, track = true, wbr = true,
            }

            function HtmlBuilder:addClass(class)
                if class and class ~= '' then
                    local current = self.attributes['class']
                    self.attributes['class'] = current and (current .. ' ' .. class) or class
                end
                return self
            end

            function HtmlBuilder:attr(name, value)
                if name and name ~= '' then
                    self.attributes[name] = value
                end
                return self
            end

            function HtmlBuilder:css(name, value)
                if name and name ~= '' and value ~= nil then
                    local entry = tostring(name) .. ':' .. tostring(value)
                    local current = self.attributes['style']
                    self.attributes['style'] = current and (current .. ';' .. entry) or entry
                end
                return self
            end

            function HtmlBuilder:cssText(text)
                if text and text ~= '' then
                    local current = self.attributes['style']
                    self.attributes['style'] = current and (current .. ';' .. text) or text
                end
                return self
            end

            function HtmlBuilder:node(node)
                if node ~= nil then
                    self.nodes[#self.nodes + 1] = node
                end
                return self
            end

            function HtmlBuilder:wikitext(text)
                if text and text ~= '' then
                    self.nodes[#self.nodes + 1] = tostring(text)
                end
                return self
            end

            function HtmlBuilder:newline()
                self.nodes[#self.nodes + 1] = '\\n'
                return self
            end

            function HtmlBuilder:tag(tagName)
                local child = HtmlBuilder.create(tagName)
                child.parent = self
                self.nodes[#self.nodes + 1] = child
                return child
            end

            function HtmlBuilder:done()
                return self.parent or self
            end

            function HtmlBuilder:allDone()
                local node = self
                while node.parent do
                    node = node.parent
                end
                return node
            end

            function HtmlBuilder:__tostring()
                local tagName = self.tagName or 'div'
                local output = { '<', tagName }
                local names = {}
                for name in pairs(self.attributes) do
                    names[#names + 1] = name
                end
                table.sort(names)
                for _, name in ipairs(names) do
                    local value = self.attributes[name]
                    if value ~= nil and value ~= false then
                        output[#output + 1] = ' ' .. name .. '="'
                        output[#output + 1] = tostring(value):gsub('"', '&quot;')
                        output[#output + 1] = '"'
                    end
                end
                if self.selfClosing or voidElements[string.lower(tagName)] then
                    output[#output + 1] = ' />'
                    return table.concat(output)
                end
                output[#output + 1] = '>'
                for _, node in ipairs(self.nodes) do
                    output[#output + 1] = tostring(node)
                end
                output[#output + 1] = '</' .. tagName .. '>'
                return table.concat(output)
            end

            function HtmlBuilder.create(tagName)
                return setmetatable({
                    tagName = tagName,
                    nodes = {},
                    attributes = {},
                }, HtmlBuilder)
            end

            function html.create(tagName)
                return HtmlBuilder.create(tagName)
            end

            return html
            """;

    /** 在模块环境里加载 mw.html 库（返回 html 表）。 */
    static LuaValue load(Globals globals) {
        return globals.load(SOURCE, "mw.html").call();
    }
}
