package cc.thonly.wikikernel.lua;

import org.luaj.vm2.Globals;

/**
 * Lua 5.1 兼容层：Scribunto 用的是 Lua 5.1，而 LuaJ 3.0 默认更接近 5.2（没有 {@code unpack}、
 * {@code table.getn}、{@code table.maxn}）。沙盒初始化时补上这些 5.1 常用入口。
 * <p>
 * 不提供 {@code setfenv} / {@code getfenv} / {@code loadstring}（沙盒安全考虑，与本站的
 * {@code load*} 一起屏蔽）。
 */
final class Lua51Shim {

    private Lua51Shim() {
    }

    private static final String SOURCE = """
            unpack = table.unpack
            table.getn = function(t)
                return #t
            end
            table.maxn = function(t)
                local maxn = 0
                for key in pairs(t) do
                    if type(key) == 'number' and key > maxn then
                        maxn = key
                    end
                end
                return maxn
            end

            -- Scribunto 的库（如 Module:Arguments）依赖 Lua 5.2 的 __pairs / __ipairs；
            -- LuaJ 默认按 5.1 的原生 next 遍历，这里补上元方法分支。
            local raw_pairs = pairs
            local raw_ipairs = ipairs
            pairs = function(t)
                local mt = getmetatable(t)
                if mt and mt.__pairs then
                    return mt.__pairs(t)
                end
                return raw_pairs(t)
            end
            ipairs = function(t)
                local mt = getmetatable(t)
                if mt and mt.__ipairs then
                    return mt.__ipairs(t)
                end
                return raw_ipairs(t)
            end
            """;

    /** 在沙盒 Globals 里安装 5.1 兼容入口（幂等）。 */
    static void install(Globals globals) {
        globals.load(SOURCE, "@lua51shim").call();
    }
}
