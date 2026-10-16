package cc.thonly.wikikernel.plugin.cite.hook;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.plugin.cite.CitePlugin;
import cc.thonly.wikikernel.plugin.cite.service.CiteState;
import cc.thonly.wikikernel.common.service.WikiText;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code <references />}（别名 {@code <reference />}）：在该位置生成脚注列表
 * （{@code <ol class="references">}，条目正文按 wikitext 渲染，带回跳箭头）。
 * <p>
 * 只有**第一个** {@code <references/>} 出列表，其余位置清掉（与迁移前 core 行为一致）；
 * 列表延迟到整页 HTML 收尾时生成——那时才收得齐页面上所有 {@code <ref>}。
 */
@Component
public class ReferencesTag implements ParserTag {

    public static final String TAG_NAME = "references";

    private final WikiText wikiText;

    public ReferencesTag(WikiText wikiText) {
        this.wikiText = wikiText;
    }

    @Override
    public String name() {
        return TAG_NAME;
    }

    @Override
    public List<String> names() {
        return List.of("references", "reference");
    }

    @Override
    public boolean expandBody() {
        return true;
    }

    @Override
    public String render(ParserTagCall call) {
        call.context().setProperty(CiteState.USED_PROPERTY, "1");
        CiteState state = CiteState.of(call.context());
        if (!state.takeList()) {
            return "";
        }
        String title = call.context().pageTitle();
        return call.context().deferHtml(() -> state.listHtml(title, wikiText));
    }
}
