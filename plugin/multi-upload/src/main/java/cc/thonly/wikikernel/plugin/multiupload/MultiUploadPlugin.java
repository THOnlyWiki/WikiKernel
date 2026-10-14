package cc.thonly.wikikernel.plugin.multiupload;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * 多文件批量上传（仿 MediaWiki 的 MsUpload / 上传向导；core 的「多文件批量上传」开关与界面迁入本插件）。
 * <p>
 * 两处增强：特殊:上传文件 的多选列表（每个文件可单独改名、逐个上传、各自成文件页、完成后插入「最近上传」画廊）
 * 与编辑页工具栏「上传并插入图片」对话框的多选批量插入。上传仍走 core 的
 * {@code POST /api/files/upload}（权限 / 审核 / 上限都在 core 侧，插件只做前端编排）。
 * <p>
 * 开关沿用 core 的功能开关 {@code features.multi-upload}（**默认关闭**；{@code /init}、
 * 站点设置、{@code LocalSettings.yaml} 都可改）。插件未加载时上传页就是 core 的单文件表单。
 */
public class MultiUploadPlugin implements WikiPlugin {

    /** 功能开关键（与 core 的站点设置 / LocalSettings 一致）。 */
    public static final String ENABLED_KEY = "features.multi-upload";

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private MessageSource messageSource;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("multi-upload", "MsUpload", "1.0.0",
                "多文件批量上传：上传页多选列表（可改名 / 逐个上传 / 插入画廊）与编辑器上传对话框批量插入");
    }

    @Override
    public void onStart(PluginContext context) {
        MultiUploadSupport support = new MultiUploadSupport(siteConfig, messageSource);
        context.register(MessageBundle.class, new MultiUploadMessages());
        context.register(HtmlHeadProvider.class,
                new MultiUploadHeadProvider(support));
        context.logger().log(System.Logger.Level.INFO, support.enabled()
                ? "已启用多文件批量上传：上传页多选 + 编辑器上传对话框批量插入（/plugins/multi-upload/）"
                : "多文件批量上传已关闭（features.multi-upload=false）：上传页为单文件表单");
    }
}