package cc.thonly.wikikernel.plugin.cite.hook;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.plugin.cite.service.CiteState;
import cc.thonly.wikikernel.common.service.WikiText;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * {@code <ref>出处</ref>} / {@code <ref name="x">出处</ref>} / {@code <ref name="x" />}（复用）：
 * 登记一条脚注，返回一个「整页 HTML 收尾时生成」的上标标记（{@code deferHtml}）。
 * <p>
 * 同名引用合并成一条、编号按首次出现（先使用后定义也可以）；错误（空内容 / 重名定义内容不同 /
 * 用了没定义的 name / 自闭合没写 name）就地显示红色的 Cite 错误提示。
 * <p>
 * 注：标签内容不参与模板展开，内容在生成列表时按 wikitext 渲染（{{模板}} / [[链接]] / '''粗体''' 都支持）。
 */
@Component
public class RefTag implements ParserTag {

    public static final String TAG_NAME = "ref";

    private final MessageSource messageSource;

    public RefTag(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    private String message(String key, String argument) {
        String text = key;
        try {
            String value = messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
            text = value == null || value.isBlank() ? key : value;
        } catch (RuntimeException ignored) {
            text = key;
        }
        return argument == null ? text : text.replace("$1", argument);
    }

    @Override
    public String name() {
        return TAG_NAME;
    }

    /** 正文随页面展开（模板生成的 {@code <ref>} 也能收齐）；引文内容在生成列表时按 wikitext 渲染。 */
    @Override
    public boolean expandBody() {
        return true;
    }

    @Override
    public String render(ParserTagCall call) {
        call.context().setProperty(CiteState.USED_PROPERTY, "1");
        CiteState state = CiteState.of(call.context());
        String name = call.attributes() == null ? null : call.attributes().get("name");
        String body = call.body() == null ? "" : call.body();
        String content = body.trim();
        // 空内容：有 name 当「复用」，没有 name 是错误（模板里常见的 <ref name="x" />）
        int index = state.addRef(name == null || name.isBlank() ? null : name.trim(),
                content, content.isEmpty());
        return call.context().deferHtml(() -> state.markerHtml(index, this::message));
    }
}
