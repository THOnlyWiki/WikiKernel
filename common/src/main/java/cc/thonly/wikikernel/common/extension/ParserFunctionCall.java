package cc.thonly.wikikernel.common.extension;

import java.util.List;

/**
 * 解析器函数调用信息：{@code {{#name:...}}} 里的参数与展开能力。
 */
public interface ParserFunctionCall {

    /** 本次渲染上下文（页面属性 / 渲染变量可写在这里）。 */
    WikiRenderContext context();

    /**
     * 原始参数：按顶层 {@code |} 拆分、未展开（与 MediaWiki 一致，
     * {@code #if} 这类需要自己决定展开时机）。命名参数（{@code key=value}）与位置参数都在列表里。
     */
    List<String> args();

    /** 当前页面标题。 */
    default String pageTitle() {
        return context().pageTitle();
    }

    /** 按当前模板 frame 展开一段 wikitext（结果不经过 HTML 白名单，按 wikitext 继续处理）。 */
    String expand(String wikitext);

    /**
     * 把一段 HTML 登记为**可信块**：把返回值放进函数输出即可，渲染收尾时原样插入正文
     * （不经过 HTML 白名单，与 {@code ParserTag} 的输出同一机制）。
     * <p>
     * 微件（Widgets）这类「内容本身就是 HTML」的扩展需要它；请自行保证内容可信
     * （例如只有特定用户组能编辑来源页面）。
     *
     * @param html 要原样插入的 HTML
     * @return 代表该片段的占位串（放进输出文本里）
     */
    default String trustedHtml(String html) {
        throw new UnsupportedOperationException("当前渲染上下文不支持可信 HTML 块");
    }
}
