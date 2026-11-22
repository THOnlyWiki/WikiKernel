package cc.thonly.wikikernel.common.parser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Arithmetic and comparison syntax shared by ParserFunctions and Scribunto. */
public final class MediaWikiExpression {
    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(\\d+(?:\\.\\d*)?|\\.\\d+|[a-z]+|<=|>=|<>|!=|==|[^\\s])", Pattern.CASE_INSENSITIVE);
    private final Matcher tokens;
    private String token;

    private MediaWikiExpression(String text) {
        tokens = TOKEN.matcher(text.replace("−", "-").replace("&minus;", "-").replace("&nbsp;", " "));
        advance();
    }

    public static double evaluate(String text) {
        if (text.length() > 8192) {
            throw new IllegalArgumentException();
        }
        MediaWikiExpression expression = new MediaWikiExpression(text);
        double result = expression.parse(1, 0);
        if (!expression.token.isEmpty() || !Double.isFinite(result)) {
            throw new IllegalArgumentException();
        }
        return result;
    }

    public static String display(double result) {
        BigDecimal number = BigDecimal.valueOf(result).stripTrailingZeros();
        double magnitude = Math.abs(result);
        return magnitude == 0 || magnitude >= 1e-6 && magnitude < 1e14
                ? number.toPlainString() : number.toString();
    }

    private void advance() {
        token = tokens.find() ? tokens.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private double parse(int minPriority, int depth) {
        if (depth > 64 || token.isEmpty()) {
            throw new IllegalArgumentException();
        }
        String first = token;
        advance();
        double left = switch (first) {
            case "(" -> {
                double value = parse(1, depth + 1);
                if (!")".equals(token)) {
                    throw new IllegalArgumentException();
                }
                advance();
                yield value;
            }
            case "pi" -> Math.PI;
            case "+", "-", "not", "abs", "floor", "ceil", "trunc", "sqrt",
                 "sin", "cos", "tan", "asin", "acos", "atan", "exp", "ln" ->
                    unary(first, parse(10, depth + 1));
            default -> Double.parseDouble(first);
        };
        while (priority(token) >= minPriority) {
            String operator = token;
            advance();
            double right = parse(priority(operator) + 1, depth + 1);
            left = binary(operator, left, right);
        }
        return left;
    }

    private static int priority(String token) {
        return switch (token) {
            case "or" -> 2;
            case "and" -> 3;
            case "=", "==", "<>", "!=", "<", ">", "<=", ">=" -> 4;
            case "round" -> 5;
            case "+", "-" -> 6;
            case "*", "/", "div", "mod", "%", "fmod" -> 7;
            case "^" -> 8;
            case "e" -> 9;
            default -> 0;
        };
    }

    private static double unary(String operator, double value) {
        return switch (operator) {
            case "+" -> value;
            case "-" -> -value;
            case "not" -> truth(value == 0);
            case "abs" -> Math.abs(value);
            case "floor" -> Math.floor(value);
            case "ceil" -> Math.ceil(value);
            case "trunc" -> value < 0 ? Math.ceil(value) : Math.floor(value);
            case "sqrt" -> Math.sqrt(value);
            case "sin" -> Math.sin(value);
            case "cos" -> Math.cos(value);
            case "tan" -> Math.tan(value);
            case "asin" -> Math.asin(value);
            case "acos" -> Math.acos(value);
            case "atan" -> Math.atan(value);
            case "exp" -> Math.exp(value);
            case "ln" -> Math.log(value);
            default -> throw new IllegalArgumentException();
        };
    }

    private static double binary(String operator, double a, double b) {
        double result = switch (operator) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/", "div" -> a / b;
            case "mod" -> (long) a % (long) b;
            case "%", "fmod" -> a % b;
            case "^" -> Math.pow(a, b);
            case "e" -> a * Math.pow(10, b);
            case "round" -> BigDecimal.valueOf(a)
                    .setScale(Math.clamp((int) b, -310, 310), RoundingMode.HALF_UP).doubleValue();
            case "=", "==" -> truth(a == b);
            case "<>", "!=" -> truth(a != b);
            case "<" -> truth(a < b);
            case ">" -> truth(a > b);
            case "<=" -> truth(a <= b);
            case ">=" -> truth(a >= b);
            case "and" -> truth(a != 0 && b != 0);
            case "or" -> truth(a != 0 || b != 0);
            default -> throw new IllegalArgumentException();
        };
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException();
        }
        return result;
    }

    private static double truth(boolean value) {
        return value ? 1 : 0;
    }
}
