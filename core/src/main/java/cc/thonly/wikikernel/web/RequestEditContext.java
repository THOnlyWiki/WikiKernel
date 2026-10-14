package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.extension.EditContext;
import cc.thonly.wikikernel.common.extension.EditFormContext;
import cc.thonly.wikikernel.common.extension.SessionAttributes;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.http.RequestMetadata;
import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link EditContext} / {@link EditFormContext} 的请求实现：网页编辑表单与 Action API 共用。
 * <p>
 * 网页表单（{@code POST /wiki/标题}）用 {@link #web}（来源 {@code web}），
 * {@code api.php?action=edit} 用 {@link #api}（来源 {@code api}），
 * AI 代理等站内写入路径用 {@link #ai}（来源 {@code ai}）——
 * 钩子据此决定是否拦截（例如验证码只拦网页保存）。
 */
public final class RequestEditContext implements EditContext, EditFormContext {

    /**
     * 一次编辑提交 / 渲染里的取值。
     *
     * @param title          页面标题
     * @param newPage        提交时页面是否还不存在
     * @param content        正文
     * @param summary        编辑摘要
     * @param minor          是否小编辑
     * @param action         {@link EditContext#ACTION_EDIT} / {@link EditContext#ACTION_CREATE}
     * @param baseRevisionId 基准修订号（没有 = null）
     * @param parameters     表单 / API 参数（只读）
     * @param preview        是否预览请求（渲染编辑表单时用）
     */
    public record FormValues(String title, boolean newPage, String content, String summary, boolean minor,
                             String action, Long baseRevisionId, Map<String, String> parameters,
                             boolean preview) {

        public FormValues {
            title = Strings.trimToEmpty(title);
            content = content == null ? "" : content;
            summary = Strings.trimToEmpty(summary);
            action = Strings.trimToEmpty(action).isEmpty() ? ACTION_EDIT : action.trim();
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }

        /** 只填标题的渲染上下文（编辑页表单字段用）。 */
        public static FormValues of(String title, boolean newPage, Map<String, String> parameters) {
            return new FormValues(title, newPage, "", "", false, ACTION_EDIT, null, parameters, false);
        }
    }

    private final HttpServletRequest request;
    private final Viewer viewer;
    private final FormValues values;
    private final String source;
    private final SessionAttributes session;
    /** 钩子登记的变更标签（保存时写入版本；见 {@link EditContext#addTag}）。 */
    private final List<String> tags = new CopyOnWriteArrayList<>();

    private RequestEditContext(HttpServletRequest request, Viewer viewer, FormValues values, String source) {
        this.request = request;
        this.viewer = viewer == null ? Viewer.ANONYMOUS : viewer;
        this.values = values;
        this.source = source;
        this.session = RequestSessionAttributes.of(request);
    }

    /** 网页编辑表单（{@code POST /wiki/标题}）。 */
    public static RequestEditContext web(HttpServletRequest request, Viewer viewer, FormValues values) {
        return new RequestEditContext(request, viewer, values, SOURCE_WEB);
    }

    /** Action API（{@code api.php?action=edit}）。 */
    public static RequestEditContext api(HttpServletRequest request, Viewer viewer, FormValues values) {
        return new RequestEditContext(request, viewer, values, SOURCE_API);
    }

    /** 站内其它写入路径（AI 代理确认提交等）；没有请求上下文时 {@code request} 可为 null。 */
    public static RequestEditContext ai(HttpServletRequest request, Viewer viewer, FormValues values) {
        return new RequestEditContext(request, viewer, values, SOURCE_AI);
    }

    @Override
    public Viewer viewer() {
        return viewer;
    }

    @Override
    public String title() {
        return values.title();
    }

    @Override
    public boolean newPage() {
        return values.newPage();
    }

    @Override
    public String content() {
        return values.content();
    }

    @Override
    public String summary() {
        return values.summary();
    }

    @Override
    public boolean minor() {
        return values.minor();
    }

    @Override
    public String action() {
        return values.action();
    }

    @Override
    public Long baseRevisionId() {
        return values.baseRevisionId();
    }

    @Override
    public Map<String, String> parameters() {
        return values.parameters();
    }

    @Override
    public SessionAttributes session() {
        return session;
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

    @Override
    public List<String> tags() {
        return List.copyOf(tags);
    }

    @Override
    public void addTag(String tag) {
        String value = Strings.trimToEmpty(tag);
        if (!value.isEmpty() && !tags.contains(value)) {
            tags.add(value);
        }
    }

    @Override
    public boolean preview() {
        return values.preview();
    }

    /** 单值化的请求参数（多值取第一个；原样保留，不裁剪空白）。 */
    public static Map<String, String> firstValues(Map<String, String[]> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        parameters.forEach((name, values) -> {
            if (name == null) {
                return;
            }
            String value = values == null || values.length == 0 ? null : values[0];
            result.put(name, value == null ? "" : value);
        });
        return Map.copyOf(result);
    }

    /** 当前请求的全部表单 / 查询参数（多值取第一个）。 */
    public static Map<String, String> formParameters(HttpServletRequest request) {
        return request == null ? Map.of() : firstValues(request.getParameterMap());
    }
}
