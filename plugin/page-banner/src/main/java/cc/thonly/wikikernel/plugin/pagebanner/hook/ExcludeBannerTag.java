package cc.thonly.wikikernel.plugin.pagebanner.hook;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.plugin.pagebanner.service.PageBannerRenderer;

/**
 * {@code <exclude-banner-page />}：让本页不显示横幅（即使命中白名单 / 逐页配置）。
 * <p>
 * 迁移前 core 在正文里用正则剥离这个标签；迁移后由插件以**解析器标签**实现，页面写法不变。
 * 为什么不用行为开关：core 的行为开关只认 {@code __NAME__}，名字里不能出现短横线
 * （{@code WikitextRenderer} 的开关正则是 {@code __[A-Za-z0-9\u4e00-\u9fa5]+__}），
 * 所以 {@code <exclude-banner-page />} 这种 MediaWiki 标签只能走 {@link ParserTag}。
 * <p>
 * {@link #expandBody()} = {@code false}：标签内容（自闭合时为空）不参与模板展开，
 * 与迁移前 core 在模板展开**之前**剥离它的行为一致；渲染结果为空串（标签从正文剥离），
 * 只往页面属性写 {@code pagebanner-exclude=1}。
 */
public class ExcludeBannerTag implements ParserTag {

    @Override
    public String name() {
        return "exclude-banner-page";
    }

    @Override
    public boolean expandBody() {
        return false;
    }

    @Override
    public String render(ParserTagCall call) {
        call.context().setProperty(PageBannerRenderer.EXCLUDE_PROPERTY, "1");
        return "";
    }
}
