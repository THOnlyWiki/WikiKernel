package cc.thonly.wikikernel.plugin.abusefilter.service;

import cc.thonly.wikikernel.plugin.abusefilter.model.AbuseContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AbuseFilter 过滤条件的解析与求值（覆盖 MediaWiki 表达式的常用子集）：
 * <ul>
 *     <li>字面量：字符串（单/双引号，支持 {@code \n}、{@code \t}、{@code \r}、反斜杠、引号与
 *     「反斜杠 + u + 4 位十六进制」形式的 Unicode 转义）、整数 / 小数、true / false / null；</li>
 *     <li>变量：见 {@link AbuseContext#KNOWN_VARIABLES}（snake_case，与 MediaWiki 同名）；</li>
 *     <li>运算符（优先级从低到高）：{@code | or}、{@code ^}、{@code & and}、
 *     {@code == != < > <= >= = in contains like rlike irlike}、{@code + -}、{@code * / %}、
 *     一元 {@code ! not -}、{@code **}；</li>
 *     <li>函数：见 {@link AbuseValues#isKnownFunction}（lcase / length / contains_any / norm /
 *     ccnorm / ip_in_range / equals_to_any 等）。</li>
 * </ul>
 * 保存过滤器时用 {@link #compile(String)} 做语法检查（未知变量 / 函数、缺少括号都会抛
 * {@link SyntaxException}）。
 */
public final class AbuseCondition {

    /**
     * 语法错误：{@code fallbackMessage} 是中文原文（日志与缺键兜底），
     * {@code messageKey} + {@code args} 供界面按当前语言解析（见 {@code AbuseFilterApiController}）。
     */
    public static final class SyntaxException extends RuntimeException {

        private final String messageKey;
        private final Object[] args;

        public SyntaxException(String fallbackMessage) {
            this(fallbackMessage, null);
        }

        public SyntaxException(String fallbackMessage, String messageKey, Object... args) {
            super(fallbackMessage);
            this.messageKey = messageKey;
            this.args = args == null ? new Object[0] : args;
        }

        public String messageKey() {
            return messageKey;
        }

        public Object[] args() {
            return args;
        }
    }

    private final Node root;
    private final String source;

    private AbuseCondition(Node root, String source) {
        this.root = root;
        this.source = source;
    }

    /** 编译表达式；语法错误抛 {@link SyntaxException}。 */
    public static AbuseCondition compile(String expression) {
        Parser parser = new Parser(expression);
        Node root = parser.parseExpression();
        parser.expectEnd();
        validate(root);
        return new AbuseCondition(root, expression == null ? "" : expression);
    }

    /** 表达式原文。 */
    public String source() {
        return source;
    }

    /** 求值成布尔。 */
    public boolean test(AbuseContext context) {
        return AbuseValues.toBoolean(root.eval(context));
    }

    /** 语法检查：变量与函数名必须在已知集合里。 */
    private static void validate(Node node) {
        switch (node) {
            case Variable variable -> {
                if (!AbuseContext.KNOWN_VARIABLES.contains(variable.name())) {
                    throw new SyntaxException("未知变量：" + variable.name(),
                            "abusefilter.error.unknownVariable", variable.name());
                }
            }
            case FunctionCall function -> {
                if (!AbuseValues.isKnownFunction(function.name())) {
                    throw new SyntaxException("未知函数：" + function.name(),
                            "abusefilter.error.unknownFunction", function.name());
                }
                function.args().forEach(AbuseCondition::validate);
            }
            case Unary unary -> validate(unary.operand());
            case Binary binary -> {
                validate(binary.left());
                validate(binary.right());
            }
            default -> {
            }
        }
    }

    // ==================== AST ====================

    private sealed interface Node
            permits Literal, Variable, FunctionCall, Unary, Binary {
        Object eval(AbuseContext context);
    }

    private record Literal(Object value) implements Node {
        @Override
        public Object eval(AbuseContext context) {
            return value;
        }
    }

    private record Variable(String name) implements Node {
        @Override
        public Object eval(AbuseContext context) {
            return context.get(name);
        }
    }

    private record FunctionCall(String name, List<Node> args) implements Node {
        @Override
        public Object eval(AbuseContext context) {
            List<Object> values = new ArrayList<>(args.size());
            for (Node arg : args) {
                values.add(arg.eval(context));
            }
            return AbuseValues.callFunction(name, values);
        }
    }

    private record Unary(String op, Node operand) implements Node {
        @Override
        public Object eval(AbuseContext context) {
            return switch (op) {
                case "!", "not" -> !AbuseValues.toBoolean(operand.eval(context));
                case "-" -> -AbuseValues.toDouble(operand.eval(context));
                default -> throw new SyntaxException("未知的一元运算符：" + op,
                        "abusefilter.error.unknownUnary", op);
            };
        }
    }

    private record Binary(String op, Node left, Node right) implements Node {
        @Override
        public Object eval(AbuseContext context) {
            // 短路逻辑
            if ("&".equals(op) || "and".equals(op)) {
                return AbuseValues.toBoolean(left.eval(context)) && AbuseValues.toBoolean(right.eval(context));
            }
            if ("|".equals(op) || "or".equals(op)) {
                return AbuseValues.toBoolean(left.eval(context)) || AbuseValues.toBoolean(right.eval(context));
            }
            if ("^".equals(op)) {
                return AbuseValues.toBoolean(left.eval(context)) ^ AbuseValues.toBoolean(right.eval(context));
            }
            Object leftValue = left.eval(context);
            Object rightValue = right.eval(context);
            return switch (op) {
                case "==", "=" -> AbuseValues.equalsValue(leftValue, rightValue);
                case "!=" -> !AbuseValues.equalsValue(leftValue, rightValue);
                case "<" -> AbuseValues.compare(leftValue, rightValue) < 0;
                case ">" -> AbuseValues.compare(leftValue, rightValue) > 0;
                case "<=" -> AbuseValues.compare(leftValue, rightValue) <= 0;
                case ">=" -> AbuseValues.compare(leftValue, rightValue) >= 0;
                case "in" -> AbuseValues.in(leftValue, rightValue);
                case "contains" -> AbuseValues.contains(leftValue, rightValue);
                case "like" -> AbuseValues.like(leftValue, rightValue);
                case "rlike" -> AbuseValues.regex(leftValue, rightValue, false);
                case "irlike" -> AbuseValues.regex(leftValue, rightValue, true);
                case "+" -> arithmetic(leftValue, rightValue, '+');
                case "-" -> arithmetic(leftValue, rightValue, '-');
                case "*" -> arithmetic(leftValue, rightValue, '*');
                case "/" -> arithmetic(leftValue, rightValue, '/');
                case "%" -> arithmetic(leftValue, rightValue, '%');
                case "**" -> Math.pow(AbuseValues.toDouble(leftValue), AbuseValues.toDouble(rightValue));
                default -> throw new SyntaxException("未知的运算符：" + op,
                        "abusefilter.error.unknownOperator", op);
            };
        }

        /** 数字运算；两侧都是数字（或布尔 / null）时才算术，否则按字符串拼接（{@code +}）。 */
        private static Object arithmetic(Object left, Object right, char op) {
            if (op == '+' && (!isNumeric(left) || !isNumeric(right))
                    && (left instanceof String || right instanceof String)) {
                return AbuseValues.toStringValue(left) + AbuseValues.toStringValue(right);
            }
            double a = AbuseValues.toDouble(left);
            double b = AbuseValues.toDouble(right);
            return switch (op) {
                case '+' -> a + b;
                case '-' -> a - b;
                case '*' -> a * b;
                case '/' -> b == 0 ? 0d : a / b;
                case '%' -> b == 0 ? 0d : a % b;
                default -> 0d;
            };
        }

        private static boolean isNumeric(Object value) {
            return value == null || value instanceof Number || value instanceof Boolean;
        }
    }

    // ==================== 词法 ====================

    private enum Type { NUMBER, STRING, IDENT, OP, LPAREN, RPAREN, COMMA, END }

    private record Token(Type type, String text, Object value) {
    }

    private static final class Lexer {

        private final String text;
        private int pos;

        Lexer(String text) {
            this.text = text == null ? "" : text;
        }

        Token next() {
            skipIgnored();
            if (pos >= text.length()) {
                return new Token(Type.END, "", null);
            }
            char ch = text.charAt(pos);
            if (Character.isDigit(ch)
                    || (ch == '.' && pos + 1 < text.length() && Character.isDigit(text.charAt(pos + 1)))) {
                return number();
            }
            if (ch == '\'' || ch == '"') {
                return string(ch);
            }
            if (Character.isLetter(ch) || ch == '_') {
                return identifier();
            }
            return operator();
        }

        private void skipIgnored() {
            while (pos < text.length()) {
                char ch = text.charAt(pos);
                if (Character.isWhitespace(ch)) {
                    pos++;
                    continue;
                }
                // 行注释：// 或 # 到行尾
                if (ch == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') {
                    while (pos < text.length() && text.charAt(pos) != '\n') {
                        pos++;
                    }
                    continue;
                }
                if (ch == '#') {
                    while (pos < text.length() && text.charAt(pos) != '\n') {
                        pos++;
                    }
                    continue;
                }
                break;
            }
        }

        private Token number() {
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
                pos++;
            }
            String raw = text.substring(start, pos);
            Object value = raw.contains(".") ? (Object) Double.parseDouble(raw) : (Object) Long.parseLong(raw);
            return new Token(Type.NUMBER, raw, value);
        }

        private Token string(char quote) {
            pos++; // 开引号
            StringBuilder value = new StringBuilder();
            while (pos < text.length()) {
                char ch = text.charAt(pos++);
                if (ch == quote) {
                    return new Token(Type.STRING, value.toString(), value.toString());
                }
                if (ch != '\\' || pos >= text.length()) {
                    value.append(ch);
                    continue;
                }
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case 'n' -> value.append('\n');
                    case 't' -> value.append('\t');
                    case 'r' -> value.append('\r');
                    case '\\' -> value.append('\\');
                    case '\'' -> value.append('\'');
                    case '"' -> value.append('"');
                    case 'u' -> {
                        if (pos + 4 <= text.length()) {
                            value.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                    }
                    default -> value.append(escaped);
                }
            }
            throw new SyntaxException("字符串没有结束引号", "abusefilter.error.unclosedString");
        }

        private Token identifier() {
            int start = pos;
            while (pos < text.length()
                    && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            return new Token(Type.IDENT, text.substring(start, pos), null);
        }

        private Token operator() {
            char ch = text.charAt(pos);
            switch (ch) {
                case '(' -> {
                    pos++;
                    return new Token(Type.LPAREN, "(", null);
                }
                case ')' -> {
                    pos++;
                    return new Token(Type.RPAREN, ")", null);
                }
                case ',' -> {
                    pos++;
                    return new Token(Type.COMMA, ",", null);
                }
                default -> {
                }
            }
            if (pos + 1 < text.length()) {
                String two = text.substring(pos, pos + 2);
                if (List.of("**", "==", "!=", "<=", ">=").contains(two)) {
                    pos += 2;
                    return new Token(Type.OP, two, null);
                }
            }
            if ("=<>!&|^+-*/%".indexOf(ch) >= 0) {
                pos++;
                return new Token(Type.OP, String.valueOf(ch), null);
            }
            throw new SyntaxException("无法识别的字符：" + ch, "abusefilter.error.unknownChar", ch);
        }
    }

    // ==================== 语法分析 ====================

    private static final class Parser {

        private final Lexer lexer;
        private Token current;

        Parser(String expression) {
            this.lexer = new Lexer(expression);
            this.current = lexer.next();
        }

        private void advance() {
            current = lexer.next();
        }

        private boolean isOperator(String op) {
            return current.type() == Type.OP && current.text().equals(op);
        }

        private boolean isKeyword(String keyword) {
            return current.type() == Type.IDENT && current.text().equalsIgnoreCase(keyword);
        }

        void expectEnd() {
            if (current.type() != Type.END) {
                throw new SyntaxException("表达式结束后有多余内容：" + current.text(),
                        "abusefilter.error.trailingContent", current.text());
            }
        }

        Node parseExpression() {
            return parseOr();
        }

        private Node parseOr() {
            Node node = parseXor();
            while (isOperator("|") || isKeyword("or")) {
                advance();
                node = new Binary("|", node, parseXor());
            }
            return node;
        }

        private Node parseXor() {
            Node node = parseAnd();
            while (isOperator("^")) {
                advance();
                node = new Binary("^", node, parseAnd());
            }
            return node;
        }

        private Node parseAnd() {
            Node node = parseComparison();
            while (isOperator("&") || isKeyword("and")) {
                advance();
                node = new Binary("&", node, parseComparison());
            }
            return node;
        }

        private Node parseComparison() {
            Node node = parseAdditive();
            while (true) {
                if (current.type() == Type.OP
                        && List.of("==", "!=", "<", ">", "<=", ">=", "=").contains(current.text())) {
                    String op = current.text();
                    advance();
                    node = new Binary(op, node, parseAdditive());
                    continue;
                }
                if (isKeyword("in") || isKeyword("contains") || isKeyword("like")
                        || isKeyword("rlike") || isKeyword("irlike")) {
                    String op = current.text().toLowerCase(Locale.ROOT);
                    advance();
                    node = new Binary(op, node, parseAdditive());
                    continue;
                }
                return node;
            }
        }

        private Node parseAdditive() {
            Node node = parseMultiplicative();
            while (isOperator("+") || isOperator("-")) {
                String op = current.text();
                advance();
                node = new Binary(op, node, parseMultiplicative());
            }
            return node;
        }

        private Node parseMultiplicative() {
            Node node = parseUnary();
            while (isOperator("*") || isOperator("/") || isOperator("%")) {
                String op = current.text();
                advance();
                node = new Binary(op, node, parseUnary());
            }
            return node;
        }

        private Node parseUnary() {
            if (isOperator("!") || isOperator("-") || isKeyword("not")) {
                String op = current.text().toLowerCase(Locale.ROOT);
                advance();
                return new Unary(op, parseUnary());
            }
            return parsePower();
        }

        private Node parsePower() {
            Node base = parsePrimary();
            if (isOperator("**")) {
                advance();
                return new Binary("**", base, parseUnary());
            }
            return base;
        }

        private Node parsePrimary() {
            if (current.type() == Type.NUMBER) {
                Object value = current.value();
                advance();
                return new Literal(value);
            }
            if (current.type() == Type.STRING) {
                Object value = current.value();
                advance();
                return new Literal(value);
            }
            if (current.type() == Type.LPAREN) {
                advance();
                Node node = parseExpression();
                if (current.type() != Type.RPAREN) {
                    throw new SyntaxException("缺少右括号", "abusefilter.error.missingParen");
                }
                advance();
                return node;
            }
            if (current.type() == Type.IDENT) {
                String name = current.text();
                advance();
                if (isOperator("(") || current.type() == Type.LPAREN) {
                    List<Node> args = new ArrayList<>();
                    advance();
                    if (current.type() != Type.RPAREN) {
                        args.add(parseExpression());
                        while (current.type() == Type.COMMA) {
                            advance();
                            args.add(parseExpression());
                        }
                    }
                    if (current.type() != Type.RPAREN) {
                        throw new SyntaxException("函数 " + name + " 缺少右括号",
                                "abusefilter.error.functionParen", name);
                    }
                    advance();
                    return new FunctionCall(name.toLowerCase(Locale.ROOT), args);
                }
                return switch (name.toLowerCase(Locale.ROOT)) {
                    case "true" -> new Literal(true);
                    case "false" -> new Literal(false);
                    case "null" -> new Literal(null);
                    default -> new Variable(name);
                };
            }
            if (current.type() == Type.END) {
                throw new SyntaxException("表达式不完整", "abusefilter.error.incomplete");
            }
            throw new SyntaxException("无法解析的内容：" + current.text(),
                    "abusefilter.error.unparsable", current.text());
        }
    }
}
