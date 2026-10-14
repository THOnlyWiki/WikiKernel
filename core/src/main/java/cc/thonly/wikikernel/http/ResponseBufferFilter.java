package cc.thonly.wikikernel.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 放大 Servlet 响应缓冲区（默认 8KB → 64KB）。
 * <p>
 * 背景：{@code th:action} 表单渲染时，Spring Security 的 CSRF 处理器需要创建会话来保存 token。
 * 如果页面在渲染到表单之前已经超过默认缓冲并提交了响应，创建会话就会抛
 * {@code IllegalStateException: Cannot create a session after the response has been committed}，
 * 错误页被追加到已输出页面尾部（页面看起来「出错 200」、HTML 里出现两个 {@code <html>}）。
 * 匿名无会话访问 特殊:登录 / 特殊:注册 / 特殊:重置密码 时最容易触发。
 * <p>
 * 64KB 足以容纳这些页面渲染到表单之前的全部内容，让会话在响应提交前正常创建。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ResponseBufferFilter extends OncePerRequestFilter {

    /** 响应缓冲区大小：64KB（登录/注册页首个表单位于约 8.3KB 处）。 */
    private static final int BUFFER_SIZE = 64 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setBufferSize(BUFFER_SIZE);
        filterChain.doFilter(request, response);
    }
}
