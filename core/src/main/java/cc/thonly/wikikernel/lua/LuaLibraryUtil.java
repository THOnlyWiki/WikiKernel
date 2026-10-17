package cc.thonly.wikikernel.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

/**
 * Scribunto 的 {@code libraryUtil} 内置库（模块里常写 {@code require('libraryUtil')}）。
 * <p>
 * 只实现参数校验/自检这几个常用函数；类型语义与 Scribunto 一致：
 * {@code checkType(name, arg, value, expected, nilOk)}、
 * {@code checkTypeMulti(name, arg, value, types)}、
 * {@code checkTypeForNamedArg(name, key, value, expected, nilOk)}、
 * {@code makeCheckSelfFunction(...)}。
 */
final class LuaLibraryUtil {

    private LuaLibraryUtil() {
    }

    private static final String SOURCE = """
            local libraryUtil = {}

            local function matches(value, expected)
                if expected == 'any' then
                    return true
                end
                if expected == 'number' then
                    return type(value) == 'number'
                end
                return type(value) == expected
            end

            local function expectedText(expected)
                if type(expected) == 'table' then
                    return table.concat(expected, ' or ')
                end
                return tostring(expected)
            end

            local function fail(name, arg, value, expected)
                error(string.format(
                    "bad argument #%s to '%s' (%s expected, got %s)",
                    tostring(arg), tostring(name), expectedText(expected), type(value)
                ), 3)
            end

            function libraryUtil.checkType(name, arg, value, expected, nilOk)
                if value == nil and nilOk then
                    return value
                end
                if matches(value, expected) then
                    return value
                end
                fail(name, arg, value, expected)
            end

            function libraryUtil.checkTypeMulti(name, arg, value, types)
                if type(types) ~= 'table' then
                    error('checkTypeMulti: types must be a table', 2)
                end
                for _, expected in ipairs(types) do
                    if matches(value, expected) then
                        return value
                    end
                end
                fail(name, arg, value, types)
            end

            function libraryUtil.checkTypeForNamedArg(name, key, value, expected, nilOk)
                if value == nil and nilOk then
                    return value
                end
                if matches(value, expected) then
                    return value
                end
                error(string.format(
                    "bad argument '%s' to '%s' (%s expected, got %s)",
                    tostring(key), tostring(name), expectedText(expected), type(value)
                ), 3)
            end

            function libraryUtil.makeCheckSelfFunction(libraryName, libraryNameVar, library, ...)
                local allowed = {...}
                return function(self, methodName)
                    if self ~= library then
                        error(string.format(
                            "libraryUtil: %s is not the %s table (method %s)",
                            tostring(libraryNameVar or 'self'),
                            tostring(libraryName),
                            tostring(methodName or '?')
                        ), 2)
                    end
                    return self
                end
            end

            return libraryUtil
            """;

    /** 在当前模块环境加载 libraryUtil 表。 */
    static LuaValue load(Globals globals) {
        return globals.load(SOURCE, "@libraryUtil").call();
    }
}
