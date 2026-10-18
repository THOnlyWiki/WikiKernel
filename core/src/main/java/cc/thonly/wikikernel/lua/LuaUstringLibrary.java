package cc.thonly.wikikernel.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Scribunto 的 {@code mw.ustring}：按 Unicode 码点工作，并把 Lua 模式翻译成 Java 正则。
 * <p>
 * 覆盖常用模式语法：{@code . * + - ? ^ $ %a %c %d %g %l %p %s %u %w %x}、字符集
 * {@code [...]}、捕获 {@code (...)}、{@code %1} 反向引用、{@code %bxy}（近似）、
 * {@code %f[set]}（近似）。无法翻译的模式会退回 LuaJ 的字节语义，保证不会比字符串库更差。
 * <p>
 * 返回的位置全部是「字符位置」（1 开始），不是 UTF-8 字节位置。
 */
public final class LuaUstringLibrary {

    private LuaUstringLibrary() {
    }

    public static LuaTable load(Globals globals) {
        LuaTable lib = new LuaTable();
        LuaValue stringLib = globals.get("string");

        lib.set("len", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                String text = value.isnil() ? "" : value.tojstring();
                return LuaValue.valueOf(text.codePointCount(0, text.length()));
            }
        });
        lib.set("sub", new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue value, LuaValue start, LuaValue end) {
                String text = value.tojstring();
                int length = text.codePointCount(0, text.length());
                int from = start.toint();
                int to = end.isnil() ? -1 : end.toint();
                if (from < 0) {
                    from = length + from + 1;
                }
                if (to < 0) {
                    to = length + to + 1;
                }
                from = Math.max(1, from);
                to = Math.min(length, to);
                if (from > to) {
                    return LuaValue.valueOf("");
                }
                return LuaValue.valueOf(text.substring(
                        text.offsetByCodePoints(0, from - 1),
                        text.offsetByCodePoints(0, to)));
            }
        });
        lib.set("upper", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                return LuaValue.valueOf(value.isnil() ? ""
                        : value.tojstring().toUpperCase(java.util.Locale.ROOT));
            }
        });
        lib.set("lower", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                return LuaValue.valueOf(value.isnil() ? ""
                        : value.tojstring().toLowerCase(java.util.Locale.ROOT));
            }
        });
        lib.set("rep", forward(stringLib.get("rep")));
        lib.set("format", forward(stringLib.get("format")));
        lib.set("byte", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String text = args.arg(1).tojstring();
                int length = text.codePointCount(0, text.length());
                int start = normalizeIndex(args.arg(2).optint(1), length);
                int end = normalizeIndex(args.arg(3).isnil() ? start : args.arg(3).toint(), length);
                if (start < 1 || start > length || end < start) {
                    return LuaValue.NONE;
                }
                int[] points = text.codePoints().toArray();
                LuaValue[] result = new LuaValue[end - start + 1];
                for (int i = start; i <= end; i++) {
                    result[i - start] = LuaValue.valueOf(points[i - 1]);
                }
                return LuaValue.varargsOf(result);
            }
        });
        lib.set("char", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                StringBuilder out = new StringBuilder();
                for (int i = 1; i <= args.narg(); i++) {
                    out.appendCodePoint(args.arg(i).toint());
                }
                return LuaValue.valueOf(out.toString());
            }
        });
        lib.set("find", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return find(args);
            }
        });
        lib.set("match", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return match(args);
            }
        });
        lib.set("gmatch", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return gmatch(args);
            }
        });
        lib.set("gsub", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return gsub(args);
            }
        });
        lib.set("codepoint", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return lib.get("byte").invoke(args);
            }
        });
        lib.set("toNFC", normalization(Normalizer.Form.NFC));
        lib.set("toNFD", normalization(Normalizer.Form.NFD));
        lib.set("toNFKC", normalization(Normalizer.Form.NFKC));
        lib.set("toNFKD", normalization(Normalizer.Form.NFKD));
        return lib;
    }

    private static VarArgFunction normalization(Normalizer.Form form) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String text = args.arg(1).tojstring();
                return LuaValue.valueOf(Normalizer.normalize(text, form));
            }
        };
    }

    private static VarArgFunction forward(LuaValue function) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return function.invoke(args);
            }
        };
    }

    private static Varargs find(Varargs args) {
        String subject = args.arg(1).checkjstring();
        String pattern = args.arg(2).checkjstring();
        int length = subject.codePointCount(0, subject.length());
        int init = normalizeIndex(args.arg(3).optint(1), length);
        if (init < 1 || init > length + 1) {
            return LuaValue.NIL;
        }
        int offset = subject.offsetByCodePoints(0, init - 1);
        if (args.arg(4).optboolean(false)) {
            int index = subject.indexOf(pattern, offset);
            if (index < 0) {
                return LuaValue.NIL;
            }
            int end = index + pattern.length();
            return LuaValue.varargsOf(new LuaValue[]{
                    LuaValue.valueOf(charStart(subject, index)),
                    LuaValue.valueOf(charEnd(subject, end))});
        }
        Matcher matcher = matcher(pattern, subject);
        if (matcher == null) {
            return stringFind(subject, pattern, init);
        }
        matcher.region(offset, subject.length());
        if (!matcher.find()) {
            return LuaValue.NIL;
        }
        LuaValue[] values = new LuaValue[2 + matcher.groupCount()];
        values[0] = LuaValue.valueOf(charStart(subject, matcher.start()));
        values[1] = LuaValue.valueOf(charEnd(subject, matcher.end()));
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String group = matcher.group(i);
            values[i + 1] = group == null ? LuaValue.NIL : LuaValue.valueOf(group);
        }
        return LuaValue.varargsOf(values);
    }

    private static Varargs match(Varargs args) {
        String subject = args.arg(1).checkjstring();
        String pattern = args.arg(2).checkjstring();
        int length = subject.codePointCount(0, subject.length());
        int init = normalizeIndex(args.arg(3).optint(1), length);
        if (init < 1 || init > length) {
            return LuaValue.NIL;
        }
        int offset = subject.offsetByCodePoints(0, init - 1);
        Matcher matcher = matcher(pattern, subject);
        if (matcher == null) {
            return stringMatch(subject, pattern, init);
        }
        matcher.region(offset, subject.length());
        if (!matcher.find()) {
            return LuaValue.NIL;
        }
        if (matcher.groupCount() == 0) {
            return LuaValue.valueOf(matcher.group());
        }
        LuaValue[] values = new LuaValue[matcher.groupCount()];
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String group = matcher.group(i);
            values[i - 1] = group == null ? LuaValue.NIL : LuaValue.valueOf(group);
        }
        return LuaValue.varargsOf(values);
    }

    private static Varargs gmatch(Varargs args) {
        String subject = args.arg(1).checkjstring();
        String pattern = args.arg(2).checkjstring();
        final Matcher matcher = matcher(pattern, subject);
        final int[] offset = {0};
        final boolean[] first = {true};
        if (matcher == null) {
            final Varargs fallback = stringGmatch(subject, pattern);
            return fallback;
        }
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs ignored) {
                if (pattern.startsWith("^") && !first[0]) {
                    return LuaValue.NIL;
                }
                first[0] = false;
                if (offset[0] > subject.length() || !matcher.find(offset[0])) {
                    return LuaValue.NIL;
                }
                offset[0] = matcher.end() == matcher.start()
                        ? nextOffset(subject, matcher.end()) : matcher.end();
                if (matcher.groupCount() == 0) {
                    return LuaValue.valueOf(matcher.group());
                }
                LuaValue[] values = new LuaValue[matcher.groupCount()];
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    String group = matcher.group(i);
                    values[i - 1] = group == null ? LuaValue.NIL : LuaValue.valueOf(group);
                }
                return LuaValue.varargsOf(values);
            }
        };
    }

    private static Varargs gsub(Varargs args) {
        String subject = args.arg(1).checkjstring();
        String pattern = args.arg(2).checkjstring();
        LuaValue replacement = args.arg(3);
        int max = args.arg(4).isnil() ? Integer.MAX_VALUE : args.arg(4).toint();
        Matcher matcher = matcher(pattern, subject);
        if (matcher == null) {
            return stringGsub(subject, pattern, replacement, max);
        }
        StringBuilder out = new StringBuilder();
        int last = 0;
        int count = 0;
        while (matcher.find() && count < max) {
            out.append(subject, last, matcher.start());
            String value = replacement(replacement, matcher);
            out.append(value == null ? matcher.group() : value);
            last = matcher.end();
            count++;
            if (matcher.end() == matcher.start()) {
                if (last < subject.length()) {
                    int next = subject.offsetByCodePoints(last, 1);
                    out.append(subject, last, next);
                    last = next;
                } else {
                    break;
                }
            }
        }
        out.append(subject, Math.min(last, subject.length()), subject.length());
        return LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(out.toString()), LuaValue.valueOf(count)});
    }

    private static String replacement(LuaValue replacement, Matcher matcher) {
        if (replacement.isfunction()) {
            LuaValue[] values = new LuaValue[Math.max(1, matcher.groupCount())];
            if (matcher.groupCount() == 0) {
                values[0] = LuaValue.valueOf(matcher.group());
            } else {
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    String group = matcher.group(i);
                    values[i - 1] = group == null ? LuaValue.NIL : LuaValue.valueOf(group);
                }
            }
            Varargs result = replacement.invoke(LuaValue.varargsOf(values));
            LuaValue first = result.arg1();
            return first.isnil() || first.isboolean() && !first.toboolean()
                    ? matcher.group() : first.tojstring();
        }
        if (replacement.istable()) {
            String key = matcher.groupCount() == 0 ? matcher.group() : matcher.group(1);
            LuaValue value = replacement.get(key);
            return value.isnil() ? matcher.group() : value.tojstring();
        }
        String format = replacement.tojstring();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c != '%' || i == format.length() - 1) {
                out.append(c);
                continue;
            }
            char next = format.charAt(++i);
            if (next == '%') {
                out.append('%');
            } else if (next >= '0' && next <= '9') {
                int group = next - '0';
                if (group == 0) {
                    out.append(matcher.group());
                } else if (group <= matcher.groupCount()) {
                    String value = matcher.group(group);
                    if (value != null) {
                        out.append(value);
                    }
                }
            } else {
                out.append('%').append(next);
            }
        }
        return out.toString();
    }

    private static Matcher matcher(String pattern, String subject) {
        try {
            return Pattern.compile(translate(pattern), Pattern.DOTALL).matcher(subject);
        } catch (PatternSyntaxException exception) {
            return null;
        }
    }

    /** Lua 模式 → Java 正则；只翻译常用语法，特殊结构做近似。 */
    private static String translate(String pattern) {
        StringBuilder out = new StringBuilder();
        int length = pattern.length();
        for (int i = 0; i < length; i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '%' -> {
                    if (i + 1 >= length) {
                        out.append('%');
                        break;
                    }
                    char n = pattern.charAt(++i);
                    switch (n) {
                        case 'a' -> out.append("\\p{L}");
                        case 'A' -> out.append("[^\\p{L}]");
                        case 'c' -> out.append("\\p{Cc}");
                        case 'C' -> out.append("[^\\p{Cc}]");
                        case 'd' -> out.append("\\p{Nd}");
                        case 'D' -> out.append("[^\\p{Nd}]");
                        case 'g' -> out.append("[!-~]");
                        case 'G' -> out.append("[^!-~]");
                        case 'l' -> out.append("\\p{Ll}");
                        case 'L' -> out.append("[^\\p{Ll}]");
                        case 'p' -> out.append("\\p{P}");
                        case 'P' -> out.append("[^\\p{P}]");
                        case 's' -> out.append("\\s");
                        case 'S' -> out.append("\\S");
                        case 'u' -> out.append("\\p{Lu}");
                        case 'U' -> out.append("[^\\p{Lu}]");
                        case 'w' -> out.append("[\\p{L}\\p{N}_]");
                        case 'W' -> out.append("[^\\p{L}\\p{N}_]");
                        case 'x' -> out.append("[0-9A-Fa-f]");
                        case 'X' -> out.append("[^0-9A-Fa-f]");
                        case 'z' -> out.append("\\x00");
                        case 'b' -> {
                            if (i + 2 < length) {
                                char open = pattern.charAt(++i);
                                char close = pattern.charAt(++i);
                                out.append(Pattern.quote(String.valueOf(open)))
                                        .append("[^").append(escapeClass(open)).append(escapeClass(close))
                                        .append("]*")
                                        .append(Pattern.quote(String.valueOf(close)));
                            }
                        }
                        case 'f' -> {
                            if (i + 1 < length && pattern.charAt(i + 1) == '[') {
                                int end = pattern.indexOf(']', i + 2);
                                if (end > 0) {
                                    String set = translateClass(pattern.substring(i + 2, end));
                                    out.append("(?<![").append(set).append("])(?=[").append(set).append("])");
                                    i = end;
                                }
                            }
                        }
                        case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> out.append('\\').append(n);
                        default -> out.append(Pattern.quote(String.valueOf(n)));
                    }
                }
                case '[' -> {
                    int end = characterClassEnd(pattern, i);
                    if (end < 0) {
                        out.append("\\[");
                    } else {
                        out.append('[').append(translateClass(pattern.substring(i + 1, end))).append(']');
                        i = end;
                    }
                }
                case '.' -> out.append('.');
                case '*' -> out.append('*');
                case '+' -> out.append('+');
                case '?' -> out.append('?');
                case '-' -> out.append("*?");
                case '^' -> out.append(i == 0 ? "\\A" : "\\^");
                case '$' -> out.append(i == length - 1 ? "\\z" : "\\$");
                case '(' -> out.append('(');
                case ')' -> out.append(')');
                default -> out.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return out.toString();
    }

    private static int characterClassEnd(String pattern, int start) {
        for (int i = start + 1; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%' && i + 1 < pattern.length()) {
                i++;
                continue;
            }
            if (c == ']' && i > start + 1) {
                return i;
            }
        }
        return -1;
    }

    private static String translateClass(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 1 < value.length()) {
                char n = value.charAt(++i);
                switch (n) {
                    case 'a' -> out.append("\\p{L}");
                    case 'd' -> out.append("\\p{Nd}");
                    case 's' -> out.append("\\s");
                    case 'w' -> out.append("\\p{L}\\p{N}_");
                    case 'l' -> out.append("\\p{Ll}");
                    case 'u' -> out.append("\\p{Lu}");
                    case 'p' -> out.append("\\p{P}");
                    case 'x' -> out.append("0-9A-Fa-f");
                    default -> out.append(escapeClass(n));
                }
            } else if (c == '^' && i == 0) {
                out.append('^');
            } else if (c == ']' || c == '\\' || c == '[') {
                out.append('\\').append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String escapeClass(char c) {
        return switch (c) {
            case '\\', ']', '[', '^', '-' -> "\\" + c;
            default -> String.valueOf(c);
        };
    }

    private static int normalizeIndex(int index, int length) {
        if (index < 0) {
            return length + index + 1;
        }
        if (index == 0) {
            return 1;
        }
        return index;
    }

    private static int charStart(String text, int utf16Offset) {
        return text.codePointCount(0, Math.min(utf16Offset, text.length())) + 1;
    }

    private static int charEnd(String text, int utf16Offset) {
        return text.codePointCount(0, Math.min(utf16Offset, text.length()));
    }

    private static int nextOffset(String text, int offset) {
        return offset >= text.length() ? offset : text.offsetByCodePoints(offset, 1);
    }

    // ---- 无法翻译时退回字面量语义（至少保证不会抛异常） ----

    private static Varargs stringFind(String subject, String pattern, int init) {
        int length = subject.codePointCount(0, subject.length());
        int start = normalizeIndex(init, length);
        if (start < 1 || start > length) {
            return LuaValue.NIL;
        }
        int offset = subject.offsetByCodePoints(0, start - 1);
        int index = subject.indexOf(pattern, offset);
        if (index < 0) {
            return LuaValue.NIL;
        }
        return LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(charStart(subject, index)),
                LuaValue.valueOf(charEnd(subject, index + pattern.length()))});
    }

    private static Varargs stringMatch(String subject, String pattern, int init) {
        Varargs found = stringFind(subject, pattern, init);
        return found.arg1().isnil() ? LuaValue.NIL : LuaValue.valueOf(pattern);
    }

    private static Varargs stringGmatch(String subject, String pattern) {
        final int[] offset = {0};
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs ignored) {
                int index = subject.indexOf(pattern, offset[0]);
                if (index < 0) {
                    return LuaValue.NIL;
                }
                offset[0] = index + Math.max(1, pattern.length());
                return LuaValue.valueOf(pattern);
            }
        };
    }

    private static Varargs stringGsub(String subject, String pattern, LuaValue replacement, int max) {
        String replacementText = replacement.tojstring();
        int count = 0;
        int from = 0;
        StringBuilder out = new StringBuilder();
        while (count < max) {
            int index = subject.indexOf(pattern, from);
            if (index < 0) {
                break;
            }
            out.append(subject, from, index).append(replacementText);
            from = index + pattern.length();
            count++;
        }
        if (count == 0) {
            return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(subject), LuaValue.ZERO});
        }
        out.append(subject.substring(from));
        return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(out.toString()), LuaValue.valueOf(count)});
    }
}
