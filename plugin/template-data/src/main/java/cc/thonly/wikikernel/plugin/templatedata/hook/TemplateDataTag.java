package cc.thonly.wikikernel.plugin.templatedata.hook;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.plugin.templatedata.TemplateDataConfig;
import cc.thonly.wikikernel.plugin.templatedata.TemplateDataMessages;
import cc.thonly.wikikernel.plugin.templatedata.service.TemplateDataBlob;
import cc.thonly.wikikernel.plugin.templatedata.service.TemplateDataHtmlFormatter;
import java.util.List;

/**
 * {@code <templatedata>} 解析标签（移植 {@code Hooks::render}，REL1_43）。
 * <p>
 * 标签内容是原始 JSON，不参与模板展开（{@link #expandBody()} 返回 {@code false}）：
 * <ul>
 *     <li>校验通过 → 把规范化后的 JSON 写进页面属性 {@code templatedata}
 *     （渲染结束后由 core 持久化，等价于 MediaWiki 的 {@code page_props}），并渲染模板文档表格；</li>
 *     <li>校验失败 → 返回错误框且<b>不</b>写页面属性（原版还会在保存时用 MultiContentSave 拦下来，
 *     WikiKernel 没有保存钩子，只在渲染时提示）；</li>
 *     <li>子页面 {@code /文档}、{@code /doc} 不写属性（对齐原版 T54448：文档子页面不该作为模板出现在 API 里）。</li>
 * </ul>
 */
public final class TemplateDataTag implements ParserTag {

    /** 标签名。 */
    public static final String TAG_NAME = "templatedata";

    /** 渲染上下文标记：样式片段只输出一次。 */
    private static final String STYLE_MARKER = "template-data-styles";

    private static final List<String> DOC_SUBPAGES = List.of("文档", "doc");

    private final TemplateDataConfig config;
    private final TemplateDataMessages fixedMessages;

    public TemplateDataTag(TemplateDataConfig config) {
        this(config, null);
    }

    /**
     * @param config        插件配置
     * @param fixedMessages 固定消息表（测试用）；为 null 时每次渲染按系统语言解析
     */
    public TemplateDataTag(TemplateDataConfig config, TemplateDataMessages fixedMessages) {
        this.config = config;
        this.fixedMessages = fixedMessages;
    }

    @Override
    public String name() {
        return TAG_NAME;
    }

    @Override
    public boolean expandBody() {
        return false;
    }

    @Override
    public String render(ParserTagCall call) {
        TemplateDataMessages messages = messages();
        TemplateDataBlob blob = TemplateDataBlob.parse(
                call.body(), config.contentLanguage(), config.generateParamOrder());

        if (!blob.status().isOk()) {
            // 校验失败：显示错误框，不写页面属性
            return TemplateDataHtmlFormatter.errorBox(blob.status(), messages);
        }

        WikiRenderContext context = call.context();
        if (context != null && !isDocSubpage(context.pageTitle())) {
            // 文档子页面不写属性（T54448）
            context.setProperty(TemplateDataBlob.PROPERTY_NAME, blob.getJsonForDatabase());
        }

        StringBuilder html = new StringBuilder();
        if (context != null && context.store().putIfAbsent(STYLE_MARKER, Boolean.TRUE) == null) {
            html.append(TemplateDataHtmlFormatter.styles());
        }
        html.append(new TemplateDataHtmlFormatter(messages, messages.language()).getHtml(blob));
        return html.toString();
    }

    /** 本次渲染使用的消息表（zh / en）。 */
    TemplateDataMessages messages() {
        return fixedMessages != null ? fixedMessages : TemplateDataMessages.forCurrentLocale();
    }

    /**
     * 是否是模板文档子页面：{@code 模板:XXX/文档}、{@code Template:XXX/doc}（对齐原版
     * {@code templatedata-doc-subpage} 消息：中文 wiki 为「文档」，英文 wiki 为「doc」）。
     */
    static boolean isDocSubpage(String pageTitle) {
        if (pageTitle == null) {
            return false;
        }
        int slash = pageTitle.lastIndexOf('/');
        if (slash < 0 || slash == pageTitle.length() - 1) {
            return false;
        }
        String subpage = pageTitle.substring(slash + 1).trim();
        for (String name : DOC_SUBPAGES) {
            if (name.equals(subpage) || (isAscii(name) && name.equalsIgnoreCase(subpage))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
