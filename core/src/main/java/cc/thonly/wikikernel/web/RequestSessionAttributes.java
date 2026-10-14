package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.extension.SessionAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * {@link SessionAttributes} 的请求实现：包住 HTTP Session 供插件钩子读写（插件不依赖 Servlet API）。
 * <p>
 * 写入时会按需创建会话（{@code request.getSession(true)}），与迁移前验证码直接调用
 * {@code request.getSession()} 的行为一致；读取不会创建会话。
 */
public final class RequestSessionAttributes implements SessionAttributes {

    private final HttpServletRequest request;

    private RequestSessionAttributes(HttpServletRequest request) {
        this.request = request;
    }

    /** 绑定当前请求的会话桥；没有请求时返回 {@link SessionAttributes#NONE}。 */
    public static SessionAttributes of(HttpServletRequest request) {
        return request == null ? SessionAttributes.NONE : new RequestSessionAttributes(request);
    }

    @Override
    public Object getAttribute(String name) {
        HttpSession session = request.getSession(false);
        return session == null ? null : session.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            removeAttribute(name);
            return;
        }
        request.getSession(true).setAttribute(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(name);
        }
    }
}
