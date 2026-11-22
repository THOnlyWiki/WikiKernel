package cc.thonly.wikikernel.plugin.parserfunctions;

import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.parser.MediaWikiExpression;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public final class ParserFunctionsPlugin implements WikiPlugin {
    @Autowired private MessageSource messages;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("parser-functions", "ParserFunctions", "1.0.0", "MediaWiki parser functions");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new Messages());
        ZoneId local = ZoneId.of(context.config().get("local-timezone",
                ZoneId.systemDefault().getId()));
        for (String name : List.of("if", "ifeq", "ifexist", "iferror", "ifexpr", "switch",
                "expr", "time", "timel", "rel2abs", "titleparts")) {
            context.register(ParserFunction.class, new Function(name, local));
        }
        if (context.config().getBoolean("enable-string-functions", true)) {
            for (String name : List.of("len", "pos", "rpos", "sub", "count", "replace", "explode", "urldecode")) {
                context.register(ParserFunction.class, new Function(name, local));
            }
        }
    }

    private static final class Messages extends ResourceMessageBundle {}

    private final class Function implements ParserFunction {
        private final String name;
        private final ZoneId local;

        private Function(String name, ZoneId local) {
            this.name = name;
            this.local = local;
        }

        @Override public String name() { return name; }
        @Override public boolean replacesBuiltIn() { return true; }

        @Override
        public String invoke(ParserFunctionCall call) {
            String first = at(call, 0);
            try {
                return switch (name) {
                    case "if" -> at(call, first.isEmpty() ? 2 : 1);
                    case "ifeq" -> at(call, equal(first, at(call, 1)) ? 2 : 3);
                    case "ifexist" -> at(call, call.pageExists(first) ? 1 : 2);
                    case "ifexpr" -> at(call, first.isEmpty() || MediaWikiExpression.evaluate(first) == 0 ? 2 : 1);
                    case "iferror" -> errorMarkup(first) ? at(call, 1)
                            : call.args().size() > 2 ? at(call, 2) : first;
                    case "switch" -> switchValue(call, first);
                    case "expr" -> first.isEmpty() ? "" : MediaWikiExpression.display(MediaWikiExpression.evaluate(first));
                    case "time", "timel" -> {
                        String date = at(call, 1);
                        String language = at(call, 2);
                        String timezone = at(call, 3);
                        yield ParserTime.format(call, first, date, language,
                                name.equals("timel") || !timezone.isEmpty() && !"0".equals(timezone), local);
                    }
                    case "titleparts" -> titleParts(first, integer(at(call, 1)), integer(at(call, 2)));
                    case "rel2abs" -> {
                        String base = at(call, 1);
                        yield relative(first, base.isEmpty() ? call.pageTitle() : base);
                    }
                    default -> stringFunction(name, first, call);
                };
            } catch (IllegalArgumentException | java.time.DateTimeException | ArithmeticException invalid) {
                return "<strong class=\"error wiki-error\">" + messages.getMessage("parserFunctions.error",
                        null, "Invalid parser function argument", LocaleContextHolder.getLocale()) + "</strong>";
            }
        }
    }

    private static String at(ParserFunctionCall call, int index) {
        return index < call.args().size() ? call.expand(call.args().get(index)).strip() : "";
    }

    private static boolean equal(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean errorMarkup(String text) {
        return Pattern.compile("(?is)<(?:strong|div|p|span)\\b[^>]*\\bclass\\s*=\\s*[\"'][^\"']*\\b(?:error|wiki-error)\\b")
                .matcher(text).find() || text.contains("wiki-error");
    }

    private static String switchValue(ParserFunctionCall call, String first) {
        boolean matchedGroup = false;
        boolean defaultGroup = false;
        String fallback = "";
        for (int i = 1; i < call.args().size(); i++) {
            String arg = call.args().get(i);
            int equals = call.assignmentSeparator(arg);
            if (equals < 0) {
                String label = call.expand(arg).strip();
                matchedGroup |= equal(first, label);
                defaultGroup |= "#default".equalsIgnoreCase(label);
                if (i == call.args().size() - 1) {
                    return label;
                }
            } else {
                String label = call.expand(arg.substring(0, equals)).strip();
                String result = arg.substring(equals + 1);
                if (matchedGroup || equal(first, label)) {
                    return call.expand(result).strip();
                }
                if (defaultGroup || "#default".equalsIgnoreCase(label)) {
                    fallback = result;
                    defaultGroup = false;
                }
            }
        }
        return call.expand(fallback).strip();
    }

    private static String titleParts(String title, int count, int start) {
        String[] parts = title.replace('_', ' ').split("/", 25);
        int from = (int) Math.clamp(start < 0 ? (long) parts.length + start : Math.max(0, start - 1), 0, parts.length);
        int to = (int) Math.clamp(count < 0 ? (long) parts.length + count
                : count == 0 ? parts.length : (long) from + count, from, parts.length);
        return String.join("/", Arrays.copyOfRange(parts, from, to));
    }

    private static String relative(String path, String base) {
        if (path.isEmpty() || ".".equals(path)) {
            return base;
        }
        boolean relative = path.startsWith("/") || path.startsWith("./") || path.startsWith("../")
                || "..".equals(path);
        var segments = new ArrayDeque<String>();
        for (String piece : (relative ? base + "/" + path : path).split("/+")) {
            if ("..".equals(piece)) {
                if (segments.pollLast() == null) {
                    throw new IllegalArgumentException();
                }
            } else if (!piece.isEmpty() && !".".equals(piece)) {
                segments.addLast(piece);
            }
        }
        return String.join("/", segments);
    }

    private static String stringFunction(String name, String source, ParserFunctionCall call) {
        String search = at(call, 1);
        String needle = search.isEmpty() ? " " : search;
        if (source.length() > 64_000 || needle.length() > 64_000) {
            throw new IllegalArgumentException();
        }
        int length = source.codePointCount(0, source.length());
        return switch (name) {
            case "len" -> String.valueOf(length);
            case "pos" -> {
                int offset = normalizeOffset(integer(at(call, 2)), length);
                int index = source.indexOf(needle, source.offsetByCodePoints(0, offset));
                yield index < 0 ? "" : String.valueOf(source.codePointCount(0, index));
            }
            case "rpos" -> {
                int index = source.lastIndexOf(needle);
                yield index < 0 ? "-1" : String.valueOf(source.codePointCount(0, index));
            }
            case "sub" -> {
                int start = normalizeOffset(integer(search), length);
                int count = integer(at(call, 2));
                int end = (int) Math.clamp(count < 0 ? (long) length + count
                        : count == 0 ? length : (long) start + count, start, length);
                yield source.substring(source.offsetByCodePoints(0, start), source.offsetByCodePoints(0, end));
            }
            case "count" -> {
                long n = 0;
                for (int i = source.indexOf(needle); i >= 0; i = source.indexOf(needle, i + needle.length())) {
                    n++;
                }
                yield Long.toString(n);
            }
            case "replace" -> {
                String replacement = at(call, 2);
                if ((long) source.length() + (long) (source.length() / needle.length()) * replacement.length() > 256_000) {
                    throw new IllegalArgumentException();
                }
                yield source.replace(needle, replacement);
            }
            case "explode" -> {
                int limit = integer(at(call, 3));
                String[] parts = source.split(Pattern.quote(needle), limit > 0 ? limit : -1);
                int index = integer(at(call, 2));
                if (index < 0) {
                    index += parts.length;
                }
                yield index < 0 || index >= parts.length ? "" : parts[index];
            }
            case "urldecode" -> URLDecoder.decode(source, StandardCharsets.UTF_8);
            default -> "";
        };
    }

    private static int normalizeOffset(int start, int length) {
        return (int) Math.clamp(start < 0 ? (long) length + start : start, 0, length);
    }

    private static int integer(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }
}
