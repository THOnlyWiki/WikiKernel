package cc.thonly.wikikernel.plugin.pagebanner;

import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.SetupStep;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.FileStore;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.pagebanner.hook.ExcludeBannerTag;
import cc.thonly.wikikernel.plugin.pagebanner.hook.PageBannerDirective;
import cc.thonly.wikikernel.plugin.pagebanner.hook.PageBannerHook;
import cc.thonly.wikikernel.plugin.pagebanner.hook.PageBannerMessages;
import cc.thonly.wikikernel.plugin.pagebanner.service.PageBannerRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * 页面横幅移植（对应 MediaWiki 的 {@code WikidataPageBanner} 扩展）：
 * <ul>
 *     <li>{@code {{PAGEBANNER:文件名|caption=说明}}} 解析器函数（{@link PageBannerDirective}）：把参数写进页面属性
 *     （多个文件名用 {@code |} 分隔 = 本页轮播这几张）；</li>
 *     <li>{@code <exclude-banner-page />} 解析器标签（{@link ExcludeBannerTag}）：本页不显示横幅；</li>
 *     <li>横幅 HTML 生成（{@link PageBannerRenderer}）：白名单模式、逐页配置、轮换机制、图片池、caption、
 *     {@code {{PAGEBANNER:…}}} 优先；该页可用的**全部**图片按稳定顺序渲染成轮播（只有一张时退化为静态横幅）；</li>
 *     <li>页面显示钩子（{@link PageBannerHook}）：把横幅插到页面大标题上方（core 皮肤的 {@code #page-banner} 容器），
 *     并按需注入插件样式 {@code /plugins/page-banner/page-banner.css} 与轮播脚本
 *     {@code /plugins/page-banner/page-banner.js}（只在本页真的有多张图片时注入）。</li>
 * </ul>
 * 设置与开关**留在 core**：{@code features.page-banner}（默认开启）以及图片池 / 白名单 / 逐页配置 / 轮换机制
 * 仍由 core 的「特殊:权限配置 → 站点设置」与 {@code /init} 表单读写，插件经 {@link SiteConfig}
 * 按同一批键读取（见 {@link PageBannerSettings}）；轮播的间隔 / 过渡 / 控件 / 悬停暂停同样按
 * {@code features.page-banner.*} 读取，并可用插件自己的配置
 * （{@code wikikernel.plugins.settings.page-banner.interval} 等短键）覆盖。图片池为空时没有默认横幅。
 */
public class PageBannerPlugin implements WikiPlugin {

    /** 功能开关键（与 core 的站点设置 / LocalSettings 一致）。 */
    public static final String ENABLED_KEY = PageBannerSettings.ENABLED_KEY;

    /** 站点设置读取桥（core 提供）。 */
    @Autowired
    private SiteConfig siteConfig;

    /** 媒体文件读取桥（core 提供）：横幅图片取直链与「是否图片」。 */
    @Autowired
    private FileStore fileStore;

    /** 消息源（core 提供，已合并插件消息）：轮播控件与设置分区的中英双语文案。 */
    @Autowired
    private MessageSource messageSource;

    /** LocalSettings.yaml 的 {@code wikikernel.features.page-banner.*}（与迁移前 core 同一批键）。 */
    @Autowired
    private PageBannerYamlSettings yamlSettings;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("page-banner", "WikidataPageBanner", "1.0.0",
                "Banner carousel above the page title, configured per page with the"
                        + " {{PAGEBANNER:file|caption=...}} directive or <exclude-banner-page />");
    }

    @Override
    public void onStart(PluginContext context) {
        PageBannerSettings settings = new PageBannerSettings(siteConfig, context.config());
        PageBannerRenderer renderer = new PageBannerRenderer(settings, fileStore);
        PageBannerMessages messages = new PageBannerMessages(messageSource);
        context.register(MessageBundle.class, messages);
        context.register(ParserFunction.class, new PageBannerDirective());
        context.register(ParserTag.class, new ExcludeBannerTag());
        context.register(PageDisplayHook.class, new PageBannerHook(renderer, messages));
        // 设置分区（站点设置页）与安装向导步骤：页面横幅的设置属于插件自己，core 只负责渲染 / 保存
        context.register(SiteSettingsSection.class, new PageBannerSettingsSection(messageSource));
        context.register(SetupStep.class, new PageBannerSetupStep(messageSource));
        importYamlSettings();
        context.logger().log(System.Logger.Level.INFO, settings.enabled()
                ? "已启用页面横幅：{{PAGEBANNER:文件名|caption=说明}} 与 <exclude-banner-page />"
                + "（图片池 / 白名单 / 逐页配置 / 轮换机制读同一批站点设置键，设置页的分类由插件自己注册；"
                + "轮播间隔 " + settings.interval() + " 秒、过渡 " + settings.transition()
                + "、控件 " + settings.controls() + "、悬停暂停 " + settings.pauseOnHover() + "）"
                : "页面横幅已关闭（features.page-banner=false）：指令仍从正文剥离，但不显示横幅");
    }

    /**
     * LocalSettings.yaml → 站点设置（与迁移前 core 的导入一致：写了的值每次启动覆盖一次，没写的键不动）。
     * <p>
     * 导入源是 {@link PageBannerYamlSettings}（{@code wikikernel.features.page-banner.*}），
     * 目标是与设置分区 / 设置页同一批 {@code features.page-banner.*} 键。
     */
    private void importYamlSettings() {
        if (siteConfig == null || yamlSettings == null) {
            return;
        }
        if (yamlSettings.getEnabled() != null) {
            siteConfig.set(PageBannerSettings.ENABLED_KEY, yamlSettings.getEnabled().toString());
        }
        if (yamlSettings.getFiles() != null) {
            siteConfig.set(PageBannerSettings.FILES_KEY, String.join("|", yamlSettings.getFiles()));
        }
        if (yamlSettings.getWhitelistEnabled() != null) {
            siteConfig.set(PageBannerSettings.WHITELIST_ENABLED_KEY,
                    yamlSettings.getWhitelistEnabled().toString());
        }
        if (yamlSettings.getWhitelist() != null) {
            siteConfig.set(PageBannerSettings.WHITELIST_KEY, String.join("|", yamlSettings.getWhitelist()));
        }
        if (yamlSettings.getRotation() != null) {
            siteConfig.set(PageBannerSettings.ROTATION_KEY, yamlSettings.getRotation().trim());
        }
        if (yamlSettings.getPerPage() != null) {
            siteConfig.set(PageBannerSettings.PER_PAGE_KEY, String.join("|", yamlSettings.getPerPage()));
        }
    }
}
