package cc.thonly.wikikernel.plugin.comments.hook;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import java.util.List;

/**
 * {@code <comment />} / {@code <comments />}（仿 MediaWiki Comments 扩展）：
 * 标签本身从正文剥离，只在页面属性里留下 {@code comments=1}，评论区由
 * {@link CommentSectionHook} 在正文下方渲染。
 * <p>
 * 关闭「评论」功能时：标签仍然剥离（页面不显示评论区），与迁移前行为一致
 * （迁移前 core 也是「标签剥离 + 功能开关决定是否显示」）。
 */
public class CommentTag implements ParserTag {

    /** 页面属性键：本页含评论标签。 */
    public static final String PROPERTY = "comments";

    @Override
    public String name() {
        return "comment";
    }

    /** 标签名兼容写法（{@code <comments />}）。 */
    @Override
    public List<String> names() {
        return List.of("comment", "comments");
    }

    /** 自闭合标签，没有内容（{@code expandBody=false} 保证不被模板展开影响）。 */
    @Override
    public boolean expandBody() {
        return false;
    }

    @Override
    public String render(ParserTagCall call) {
        call.context().setProperty(PROPERTY, "1");
        return "";
    }
}
