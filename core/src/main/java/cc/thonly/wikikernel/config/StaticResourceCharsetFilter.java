package cc.thonly.wikikernel.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * 给静态文本资源补上 UTF-8 声明。
 * <p>
 * Spring 静态资源默认只返回 {@code text/javascript} / {@code text/css}（不带 charset），
 * 浏览器直接打开 {@code /js/xxx.js} 这类地址时会按**系统默认编码**解码（中文 Windows 是 GBK），
 * 结果中文注释 / 文案显示成乱码。这里在响应提交前把没有 charset 的 {@code text/*} 补上 {@code ;charset=UTF-8}
 * （页面内通过 {@code <script src>} 加载不受影响，但直接查看源码、右键另存等场景都正常）。
 */
@Component
public class StaticResourceCharsetFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, new CharsetResponseWrapper(response));
    }

    /** 把 setContentType / Content-Type 头里的 text/* 补上 UTF-8。 */
    private static final class CharsetResponseWrapper extends HttpServletResponseWrapper {

        private CharsetResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setContentType(String type) {
            super.setContentType(withCharset(type));
        }

        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, "Content-Type".equalsIgnoreCase(name) ? withCharset(value) : value);
        }

        @Override
        public void addHeader(String name, String value) {
            super.addHeader(name, "Content-Type".equalsIgnoreCase(name) ? withCharset(value) : value);
        }

        private static String withCharset(String type) {
            if (type == null) {
                return null;
            }
            String lower = type.toLowerCase(Locale.ROOT);
            boolean textual = lower.startsWith("text/") || lower.startsWith("application/javascript");
            if (textual && !lower.contains("charset=")) {
                return type + ";charset=UTF-8";
            }
            return type;
        }
    }
}
