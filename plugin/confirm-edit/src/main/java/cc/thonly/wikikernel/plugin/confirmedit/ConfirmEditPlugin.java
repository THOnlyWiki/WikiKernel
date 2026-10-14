package cc.thonly.wikikernel.plugin.confirmedit;

import cc.thonly.wikikernel.common.extension.EditFormField;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.SetupStep;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.confirmedit.hook.ConfirmEditFormField;
import cc.thonly.wikikernel.plugin.confirmedit.hook.ConfirmEditGuard;
import cc.thonly.wikikernel.plugin.confirmedit.hook.ConfirmEditMessages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * ConfirmEdit 扩展移植（对应 MediaWiki 的 {@code ConfirmEdit} 扩展，问答式验证码）：
 * <ul>
 *     <li>{@link ConfirmEditFormField}（{@code EditFormField}）：编辑框下方插入一道随机加法题 + 答案输入框；</li>
 *     <li>{@link ConfirmEditGuard}（{@code EditGuard}）：网页保存前校验答案（答错 {@code BLOCK} → 回到编辑表单，
 *     已填内容与摘要保留、题目换成新的一道）；{@code api.php} 的来源是 {@code api}，不校验；</li>
 *     <li>{@link ConfirmEditMessages}（{@code MessageBundle}）：{@code confirmEdit.*} 文案（键名不变）。</li>
 * </ul>
 * 设置**留在 core**：开关 {@code features.confirm-edit}（默认关闭）与免验证用户组
 * {@code features.confirm-edit.bypass-groups} 仍由「特殊:权限配置 → 站点设置」与 {@code /init} 维护，
 * 插件经 {@link SiteConfig} 读同一批键（见 {@link ConfirmEditSettings}），所以开关语义不变。
 */
public class ConfirmEditPlugin implements WikiPlugin {

    /** 功能开关键（与 core 的站点设置 / LocalSettings 一致）。 */
    public static final String ENABLED_KEY = ConfirmEditSettings.ENABLED_KEY;

    /** 免验证用户组键（与 core 的站点设置一致）。 */
    public static final String BYPASS_GROUPS_KEY = ConfirmEditSettings.BYPASS_GROUPS_KEY;

    /** 站点设置读取桥（core 提供）。 */
    @Autowired
    private SiteConfig siteConfig;

    /** 本地化文案（core 的 MessageSource 已合并插件消息）。 */
    @Autowired
    private MessageSource messageSource;

    /** LocalSettings.yaml 的 {@code wikikernel.features.confirm-edit.*}（与迁移前 core 同一批键）。 */
    @Autowired
    private ConfirmEditYamlSettings yamlSettings;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("confirm-edit", "ConfirmEdit", "1.0.0",
                "Arithmetic CAPTCHA for page edits and creations, rendered on the edit form"
                        + " and checked before saving");
    }

    @Override
    public void onStart(PluginContext context) {
        ConfirmEditSupport support = new ConfirmEditSupport(siteConfig, messageSource);
        context.register(MessageBundle.class, new ConfirmEditMessages());
        context.register(EditFormField.class, new ConfirmEditFormField(support));
        context.register(EditGuard.class, new ConfirmEditGuard(support));
        // 设置分区（站点设置页）与安装向导步骤：编辑验证码的设置属于插件自己，core 只负责渲染 / 保存
        context.register(SiteSettingsSection.class, new ConfirmEditSettingsSection(messageSource));
        context.register(SetupStep.class, new ConfirmEditSetupStep(messageSource));
        importYamlSettings();
        context.logger().log(System.Logger.Level.INFO, support.enabled()
                ? "已启用编辑验证码：未豁免用户保存编辑 / 创建页面要答一道随机加法题"
                + "（免验证组读同一批站点设置键 features.confirm-edit.bypass-groups，设置页分类由插件自己注册）"
                : "编辑验证码已关闭（features.confirm-edit=false）：编辑页不显示题目，保存也不校验");
    }

    /** LocalSettings.yaml → 站点设置（与迁移前 core 的导入一致：写了的值每次启动覆盖一次，没写的键不动）。 */
    private void importYamlSettings() {
        if (siteConfig == null || yamlSettings == null) {
            return;
        }
        if (yamlSettings.getEnabled() != null) {
            siteConfig.set(ConfirmEditSettings.ENABLED_KEY, yamlSettings.getEnabled().toString());
        }
        if (yamlSettings.getBypassGroups() != null) {
            siteConfig.set(ConfirmEditSettings.BYPASS_GROUPS_KEY, String.join("|", yamlSettings.getBypassGroups()));
        }
    }
}
