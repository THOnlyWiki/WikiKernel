package cc.thonly.wikikernel.lua;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Locale;

/**
 * 修正版 {@code string.format}。
 *
 * <p>LuaJ 3.0.1 自带的实现会忽略 {@code %f}/{@code %s} 的精度、忽略浮点数的宽度
 * （例如 {@code string.format("%.2f", 23.456)} 得到 {@code "23.456"}），与标准 Lua / Scribunto 不符。
 * 这里逐段翻译成 Java Formatter 的格式并统一 {@link Locale#ROOT}，保证模块输出与 Scribunto 一致。</p>
 *
 * <p>支持：{@code %d %i %u %o %x %X %e %E %f %g %G %a %A %c %s %q %%} 以及
 * {@code -+ #0} 标志、宽度与 {@code .精度}（字符串 {@code %s} 的精度 = 截断）。</p>
 */
final class LuaFormat extends VarArgFunction {

    /** 把 {@code string.format} 替换成修正版（沙盒初始化时调用一次）。 */
    static void install(LuaValue stringLib) {
        stringLib.set("format", new LuaFormat());
    }

    @Override
    public Varargs invoke(Varargs args) {
        String pattern = args.checkjstring(1);
        StringBuilder out = new StringBuilder();
        int argumentIndex = 2;
        int index = 0;
        int length = pattern.length();
        while (index < length) {
            char current = pattern.charAt(index++);
            if (current != '%') {
                out.append(current);
                continue;
            }
            if (index >= length) {
                throw new LuaError("bad argument #1 to 'format' (invalid conversion '%')");
            }
            if (pattern.charAt(index) == '%') {
                out.append('%');
                index++;
                continue;
            }
            // [flags][width][.precision]
            StringBuilder spec = new StringBuilder("%");
            while (index < length && "-+ #0".indexOf(pattern.charAt(index)) >= 0) {
                spec.append(pattern.charAt(index++));
            }
            while (index < length && Character.isDigit(pattern.charAt(index))) {
                spec.append(pattern.charAt(index++));
            }
            if (index < length && pattern.charAt(index) == '.') {
                spec.append('.');
                index++;
                while (index < length && Character.isDigit(pattern.charAt(index))) {
                    spec.append(pattern.charAt(index++));
                }
            }
            if (index >= length) {
                throw new LuaError("bad argument #1 to 'format' (invalid conversion)");
            }
            char conversion = pattern.charAt(index++);
            LuaValue value = args.arg(argumentIndex++);
            out.append(format(spec.toString(), conversion, value));
        }
        return LuaValue.valueOf(out.toString());
    }

    private static String format(String spec, char conversion, LuaValue value) {
        switch (conversion) {
            case 'd':
            case 'i':
                return String.format(Locale.ROOT, spec + 'd', value.checklong());
            case 'u':
                // 与 C / Lua 一致：按 32 位无符号输出
                return String.format(Locale.ROOT, spec + 'd', value.checklong() & 0xFFFFFFFFL);
            case 'o':
            case 'x':
            case 'X':
                return String.format(Locale.ROOT, spec + conversion, value.checklong());
            case 'e':
            case 'E':
            case 'f':
            case 'g':
            case 'G':
            case 'a':
            case 'A':
                return String.format(Locale.ROOT, spec + conversion, value.checkdouble());
            case 'c':
                return String.format(Locale.ROOT, spec + 'c', value.checkint());
            case 's':
                return String.format(Locale.ROOT, spec + 's', value.tojstring());
            case 'q':
                return quote(value.tojstring());
            default:
                throw new LuaError("bad argument #1 to 'format' (invalid conversion '%" + conversion + "')");
        }
    }

    /** Lua 的 {@code %q}：加引号并转义引号、反斜杠、换行等（便于把任意文本重新变成合法字面量）。 */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\0' -> out.append("\\0");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
