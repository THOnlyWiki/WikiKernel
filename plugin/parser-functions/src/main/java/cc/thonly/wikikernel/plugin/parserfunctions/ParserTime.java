package cc.thonly.wikikernel.plugin.parserfunctions;

import cc.thonly.wikikernel.common.extension.ParserFunctionCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.util.Locale;
import java.util.regex.Pattern;

final class ParserTime {
    private static final Pattern RELATIVE = Pattern.compile("([+-]\\d+)\\s+(day|week|month|year|hour|minute|second)s?");
    private ParserTime() {}

    static String format(ParserFunctionCall call, String pattern, String source, String language,
                         boolean useLocal, ZoneId local) {
        if (pattern.length() > 2048 || source.length() > 256) {
            throw new IllegalArgumentException();
        }
        Instant instant = parse(call, source);
        ZonedDateTime time = instant.atZone(useLocal ? local : ZoneOffset.UTC);
        if (time.getYear() < 1 || time.getYear() > 9999) {
            throw new IllegalArgumentException();
        }
        Locale locale = language.isEmpty() ? Locale.SIMPLIFIED_CHINESE : Locale.forLanguageTag(language);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                out.append(pattern.charAt(++i));
            } else if (c == '"') {
                int end = pattern.indexOf('"', i + 1);
                if (end < 0) {
                    out.append(pattern.substring(i + 1));
                    break;
                }
                out.append(pattern, i + 1, end);
                i = end;
            } else {
                out.append(switch (c) {
                    case 'Y' -> String.format(Locale.ROOT, "%04d", time.getYear());
                    case 'y' -> two(time.getYear() % 100);
                    case 'n' -> time.getMonthValue();
                    case 'm' -> two(time.getMonthValue());
                    case 'j' -> time.getDayOfMonth();
                    case 'd' -> two(time.getDayOfMonth());
                    case 'H' -> two(time.getHour());
                    case 'G' -> time.getHour();
                    case 'g' -> time.getHour() % 12 == 0 ? 12 : time.getHour() % 12;
                    case 'h' -> two(time.getHour() % 12 == 0 ? 12 : time.getHour() % 12);
                    case 'i' -> two(time.getMinute());
                    case 's' -> two(time.getSecond());
                    case 'a' -> time.getHour() < 12 ? "am" : "pm";
                    case 'A' -> time.getHour() < 12 ? "AM" : "PM";
                    case 'U' -> instant.getEpochSecond();
                    case 'F', 'M' -> time.getMonth().getDisplayName(c == 'F' ? TextStyle.FULL : TextStyle.SHORT, locale);
                    case 'l', 'D' -> time.getDayOfWeek().getDisplayName(c == 'l' ? TextStyle.FULL : TextStyle.SHORT, locale);
                    case 'N' -> time.getDayOfWeek().getValue();
                    case 'w' -> time.getDayOfWeek().getValue() % 7;
                    case 'W' -> two(time.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
                    case 'o' -> time.get(IsoFields.WEEK_BASED_YEAR);
                    case 'z' -> time.getDayOfYear() - 1;
                    case 't' -> time.toLocalDate().lengthOfMonth();
                    case 'L' -> time.toLocalDate().isLeapYear() ? 1 : 0;
                    case 'O' -> time.format(DateTimeFormatter.ofPattern("xx", Locale.ROOT));
                    case 'P' -> time.format(DateTimeFormatter.ofPattern("xxx", Locale.ROOT));
                    case 'T' -> time.format(DateTimeFormatter.ofPattern("z", Locale.ROOT));
                    case 'Z' -> time.getOffset().getTotalSeconds();
                    case 'c' -> time.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx", Locale.ROOT));
                    case 'r' -> time.format(DateTimeFormatter.RFC_1123_DATE_TIME);
                    default -> c;
                });
            }
        }
        return out.toString();
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    private static Instant parse(ParserFunctionCall call, String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        var relative = RELATIVE.matcher(lower);
        if (source.isEmpty() || "now".equals(lower) || "today".equals(lower)
                || "tomorrow".equals(lower) || "yesterday".equals(lower) || relative.matches()) {
            call.context().setProperty(WikiRenderContext.CACHE_EXPIRY_PROPERTY, "0");
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            return switch (lower) {
                case "", "now" -> now.toInstant();
                case "today", "tomorrow", "yesterday" -> now.toLocalDate()
                        .plusDays("tomorrow".equals(lower) ? 1 : "yesterday".equals(lower) ? -1 : 0)
                        .atStartOfDay(ZoneOffset.UTC).toInstant();
                default -> shift(now, Long.parseLong(relative.group(1)), relative.group(2)).toInstant();
            };
        }
        if (source.matches("@-?\\d+|\\d{10}")) {
            return Instant.ofEpochSecond(Long.parseLong(source.replace("@", "")));
        }
        if (source.matches("\\d{14}")) {
            return LocalDateTime.parse(source, DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(
                    java.time.format.ResolverStyle.STRICT)).toInstant(ZoneOffset.UTC);
        }
        if (source.matches("\\d{8}")) {
            return LocalDate.parse(source, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        String iso = source.replaceFirst("^(\\d{4}-\\d{2}-\\d{2}) ", "$1T");
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (java.time.DateTimeException ignored) {
            return iso.contains("T") ? LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC)
                    : LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }

    private static ZonedDateTime shift(ZonedDateTime date, long n, String unit) {
        return switch (unit) {
            case "day" -> date.plusDays(n);
            case "week" -> date.plusWeeks(n);
            case "month" -> date.plusMonths(n);
            case "year" -> date.plusYears(n);
            case "hour" -> date.plusHours(n);
            case "minute" -> date.plusMinutes(n);
            default -> date.plusSeconds(n);
        };
    }
}
