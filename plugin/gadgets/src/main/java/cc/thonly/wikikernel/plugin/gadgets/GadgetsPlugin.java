package cc.thonly.wikikernel.plugin.gadgets;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.gadgets.hook.GadgetsHeadProvider;
import cc.thonly.wikikernel.plugin.gadgets.hook.GadgetsPreferenceSection;
import cc.thonly.wikikernel.plugin.gadgets.service.GadgetService;
import cc.thonly.wikikernel.plugin.gadgets.web.GadgetsSpecialPage;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Gadgets 扩展移植（对应 MediaWiki 的 {@code Gadgets} 扩展）：
 * <ul>
 *     <li>定义页 {@code WikiKernel:Gadgets-definition}，资源页在 {@code 小部件:} / {@code Gadget:} 命名空间；</li>
 *     <li>按用户加载：默认启用的 Gadget 对所有人（含匿名）生效，其余在参数设置里勾选
 *     （{@code wiki_user.gadgets}，{@code !} = 明确全部关闭）；{@code rights=组1,组2} 只对组内用户加载；</li>
 *     <li>{@code 特殊:Gadgets} 目录页、参数设置分区、{@code /api/gadgets/preferences} 保存接口；</li>
 *     <li>前端注入（脚本 / 样式 / 依赖声明）经 {@link GadgetsHeadProvider}（站点级）。</li>
 * </ul>
 * 开关沿用 core 的功能开关 {@code features.gadgets}（默认开启）；定义页与资源页的编辑权限仍在 core
 * 的访问控制里（「小部件管理员」/「小部件编辑者」），与迁移前一致。
 */
public class GadgetsPlugin implements WikiPlugin {

    /** 能编辑定义页与资源页的用户组（与 core 的权限组名一致）。 */
    public static final String GROUP_GADGET_ADMIN = "小部件管理员";

    /** 定义页标题（{@link GadgetService#DEFINITION_PAGE} 的别名，便于插件外部引用）。 */
    public static final String DEFINITION_PAGE = GadgetService.DEFINITION_PAGE;

    @Autowired
    private WikiPageStore wikiPageStore;

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private UserSettings userSettings;

    @Autowired
    private CurrentUser currentUser;

    /** Gadgets 定义解析与加载规则（Spring Bean：@RestController 与钩子共用同一个实例）。 */
    @Autowired
    private GadgetService gadgets;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("gadgets", "Gadgets", "1.0.0",
                "User-selectable JavaScript / CSS gadgets defined on a wiki page and loaded per user"
                        + " (Special:Gadgets + a Preferences section)");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new GadgetsMessages());
        context.register(HtmlHeadProvider.class, new GadgetsHeadProvider(gadgets));
        context.register(PreferenceSection.class, new GadgetsPreferenceSection(gadgets));
        context.register(SpecialPage.class, new GadgetsSpecialPage(gadgets, currentUser));
        context.logger().log(System.Logger.Level.INFO, gadgets.enabled()
                ? "已启用 Gadgets：定义页 " + GadgetService.DEFINITION_PAGE
                + "（资源页 小部件: / Gadget:；参数设置里可勾选）"
                : "Gadgets 已关闭（features.gadgets=false）：不注入脚本 / 样式，也不显示个人设置");
    }


    /**
     * 插件消息（键与迁移前 core 的 {@code gadget.*} 一致；core 里的已删除）。
     * <p>
     * 文案在插件自己的资源文件里（不再写成 Java 的 {@code Map} 字面量）：
     * <pre>
     * src/main/resources/i18n/messages.properties     中文（默认语言）
     * src/main/resources/i18n/messages_en.properties  英文
     * </pre>
     */
    static final class GadgetsMessages extends ResourceMessageBundle {
    }
}
