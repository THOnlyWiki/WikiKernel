package cc.thonly.wikikernel.actionapi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpSession;

/** JSONP is public, anonymous and stateless, even when the browser sends login cookies. */
final class JsonpRequest extends HttpServletRequestWrapper {

    JsonpRequest(HttpServletRequest request) {
        super(request);
    }

    @Override
    public HttpSession getSession(boolean create) {
        if (create) {
            throw new IllegalStateException("JSONP requests cannot create sessions");
        }
        return null;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }
}
