package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.extension.ActionContext;
import cc.thonly.wikikernel.common.extension.SessionAttributes;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.http.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ActionContext} 的请求实现：移动 / 上传 / 删除 / 封禁这类「非编辑动作」共用。
 * <p>
 * 网页表单（如 {@code 特殊:移动页面} → {@code POST /api/pages/move}）用 {@link #web}，
 * 外部 API 调用用 {@link #api}——钩子据此区分来源（与 {@link RequestEditContext} 同一套约定）。
 */
public final class RequestActionContext implements ActionContext {

    private final HttpServletRequest request;
    private final Viewer viewer;
    private final String action;
    private final String target;
    private final String summary;
    private final Map<String, String> data;
    private final Map<String, String> parameters;
    private final String source;
    private final byte[] payload;

    private RequestActionContext(HttpServletRequest request, Viewer viewer, String action, String target,
                                 String summary, Map<String, String> data, Map<String, String> parameters,
                                 String source, byte[] payload) {
        this.request = request;
        this.viewer = viewer == null ? Viewer.ANONYMOUS : viewer;
        this.action = Strings.trimToEmpty(action);
        this.target = Strings.trimToEmpty(target);
        this.summary = Strings.trimToEmpty(summary);
        this.data = data == null ? Map.of() : Map.copyOf(data);
        this.parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        this.source = source;
        this.payload = payload == null ? null : payload.clone();
    }

    /** 网页表单提交的动作。 */
    public static RequestActionContext web(HttpServletRequest request, Viewer viewer, String action,
                                           String target, String summary, Map<String, String> data) {
        return new RequestActionContext(request, viewer, action, target, summary, data,
                RequestEditContext.formParameters(request), SOURCE_WEB, null);
    }

    /** 外部 API 调用的动作。 */
    public static RequestActionContext api(HttpServletRequest request, Viewer viewer, String action,
                                           String target, String summary, Map<String, String> data) {
        return new RequestActionContext(request, viewer, action, target, summary, data,
                RequestEditContext.formParameters(request), SOURCE_API, null);
    }

    /** 不带额外数据的便捷构造（{@code data} 为空）。 */
    public static RequestActionContext web(HttpServletRequest request, Viewer viewer, String action, String target) {
        return web(request, viewer, action, target, "", Map.of());
    }

    /** 可变的 data 构造器（调用方按需 put 后再 {@link #build()}）。 */
    public static Builder builder(HttpServletRequest request, Viewer viewer, String action) {
        return new Builder(request, viewer, action);
    }

    @Override
    public Viewer viewer() {
        return viewer;
    }

    @Override
    public String action() {
        return action;
    }

    @Override
    public String target() {
        return target;
    }

    @Override
    public String summary() {
        return summary;
    }

    @Override
    public Map<String, String> data() {
        return data;
    }

    @Override
    public Map<String, String> parameters() {
        return parameters;
    }

    @Override
    public SessionAttributes session() {
        return RequestSessionAttributes.of(request);
    }

    @Override
    public String clientIp() {
        String ip = RequestMetadata.ip();
        if (!Strings.isBlank(ip)) {
            return ip;
        }
        return request == null ? "" : Strings.trimToEmpty(request.getRemoteAddr());
    }

    @Override
    public String userAgent() {
        String userAgent = RequestMetadata.userAgent();
        if (!Strings.isBlank(userAgent)) {
            return userAgent;
        }
        return request == null ? "" : Strings.trimToEmpty(request.getHeader("User-Agent"));
    }

    @Override
    public String source() {
        return source;
    }

    /** 二进制载荷（目前只有上传带文件内容；没有时为 {@code null}）。 */
    @Override
    public byte[] payload() {
        return payload == null ? null : payload.clone();
    }

    /** 逐个动作收集 data / 选择 web / api 的构造器。 */
    public static final class Builder {

        private final HttpServletRequest request;
        private final Viewer viewer;
        private final String action;
        private final Map<String, String> data = new LinkedHashMap<>();
        private String target = "";
        private String summary = "";
        private String source = SOURCE_WEB;
        private byte[] payload;

        private Builder(HttpServletRequest request, Viewer viewer, String action) {
            this.request = request;
            this.viewer = viewer;
            this.action = action;
        }

        /** 目标（页面标题 / 文件名 / 用户名）。 */
        public Builder target(String value) {
            this.target = value;
            return this;
        }

        /** 原因 / 摘要。 */
        public Builder summary(String value) {
            this.summary = value;
            return this;
        }

        /** 追加一项动作数据（值为 null 时记为空串）。 */
        public Builder data(String name, Object value) {
            this.data.put(name, value == null ? "" : String.valueOf(value));
            return this;
        }

        /** 二进制载荷（上传的文件内容；其它动作不用）。 */
        public Builder payload(byte[] value) {
            this.payload = value;
            return this;
        }

        /** 来源改成 {@link ActionContext#SOURCE_API}。 */
        public Builder apiSource() {
            this.source = SOURCE_API;
            return this;
        }

        public RequestActionContext build() {
            return new RequestActionContext(request, viewer, action, target, summary, data,
                    RequestEditContext.formParameters(request), source, payload);
        }
    }
}
