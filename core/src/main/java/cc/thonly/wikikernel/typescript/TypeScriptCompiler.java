package cc.thonly.wikikernel.typescript;

import java.util.List;

/**
 * TypeScript 服务端编译器（在 JVM 里跑官方 {@code typescript.js}，自包含、不需要主机装 Node）。
 * <p>
 * 采用 TS 的 {@code transpileModule}（逐文件转译，不做类型检查）：
 * 语法层面支持完整 TypeScript（类型注解、泛型、{@code enum}、装饰器、{@code as} 断言、TSX），
 * 转换失败时给出带行号的**语法诊断**（类型错误的检查需要完整 Program，代价与收益都不合适）。
 */
public interface TypeScriptCompiler {

    /** 编译器是否随包内置（资源存在即可，不触发引擎初始化）。 */
    boolean available();

    /** 编译器版本（作为编译缓存的键之一；读取失败时返回 "unknown"）。 */
    String version();

    /** 转译一个文件；{@code fileName} 只用于诊断信息与 TSX 判定。 */
    Result compile(String source, String fileName);

    /** 是否按 TSX 处理（{@code .tsx} 需要开启 JSX 转换）。 */
    static boolean isTsx(String fileName) {
        return fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".tsx");
    }

    /** 一条诊断（行 / 列从 1 开始；0 表示没有位置信息）。 */
    record Diagnostic(int line, int column, String message) {

        /** 展示用：{@code 3:12 消息}。 */
        public String format() {
            return line > 0 ? line + ":" + column + " " + message : message;
        }
    }

    /** 转译结果：成功时 {@code code} 非空、诊断为空；失败时 {@code code} 为空、诊断非空。 */
    record Result(String code, List<Diagnostic> diagnostics) {

        public boolean ok() {
            return code != null && diagnostics.isEmpty();
        }

        public static Result success(String code) {
            return new Result(code, List.of());
        }

        public static Result failure(String message) {
            return new Result(null, List.of(new Diagnostic(0, 0, message)));
        }

        public static Result failure(List<Diagnostic> diagnostics) {
            return new Result(null, List.copyOf(diagnostics));
        }

        /** 诊断的多行文本（存库 / 展示用）。 */
        public String diagnosticsText() {
            StringBuilder out = new StringBuilder();
            for (Diagnostic diagnostic : diagnostics) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(diagnostic.format());
            }
            return out.toString();
        }
    }
}
