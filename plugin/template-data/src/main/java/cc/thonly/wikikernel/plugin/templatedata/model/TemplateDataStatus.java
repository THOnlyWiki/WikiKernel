package cc.thonly.wikikernel.plugin.templatedata.model;

import cc.thonly.wikikernel.plugin.templatedata.TemplateDataMessages;
import java.util.ArrayList;
import java.util.List;

/**
 * TemplateData 的校验结果（对应 MediaWiki 的 {@code Status}，只保留 OK / 一个错误 + 消息参数）。
 * <p>
 * {@code code} 是消息键（如 {@code templatedata-invalid-type}），{@code params} 是 {@code $1}/{@code $2}
 * 参数；本地化由 {@link TemplateDataMessages} 在渲染 / 请求时完成。
 * 校验通过时 {@link #value()} 携带解析出来的根对象（原版是 {@code Status::getValue()}）。
 */
public final class TemplateDataStatus {

    private final String code;
    private final List<String> params;
    private final Object value;

    private TemplateDataStatus(String code, List<String> params, Object value) {
        this.code = code;
        this.params = List.copyOf(params);
        this.value = value;
    }

    /** 校验通过，并携带值（如解析出来的根对象）。 */
    public static TemplateDataStatus ok(Object value) {
        return new TemplateDataStatus(null, List.of(), value);
    }

    /** 校验失败。 */
    public static TemplateDataStatus error(String code, String... params) {
        return new TemplateDataStatus(code, List.of(params), null);
    }

    /** 校验失败（参数为列表）。 */
    public static TemplateDataStatus error(String code, List<String> params) {
        return new TemplateDataStatus(code, params == null ? List.of() : params, null);
    }

    public boolean isOk() {
        return code == null;
    }

    /** 消息键；校验通过时为 null。 */
    public String code() {
        return code;
    }

    /** 消息参数（{@code $1}、{@code $2}…）。 */
    public List<String> params() {
        return params;
    }

    /** 校验通过时携带的值（否则为 null）。 */
    public Object value() {
        return value;
    }

    /** 本地化后的消息文本（含参数替换），用于错误框与 API 错误信息。 */
    public String text(TemplateDataMessages messages) {
        if (isOk()) {
            return "";
        }
        return messages == null ? code : messages.text(code, params);
    }

    /** 便于断言 / 日志：{@code ok} 或 {@code code(params…)}。 */
    @Override
    public String toString() {
        if (isOk()) {
            return "ok";
        }
        List<String> all = new ArrayList<>(params);
        all.add(0, code);
        return String.join(", ", all);
    }
}
