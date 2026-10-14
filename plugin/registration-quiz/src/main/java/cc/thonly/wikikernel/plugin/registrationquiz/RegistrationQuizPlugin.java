package cc.thonly.wikikernel.plugin.registrationquiz;

import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.RegistrationFormField;
import cc.thonly.wikikernel.common.extension.RegistrationGuard;
import cc.thonly.wikikernel.common.extension.SetupStep;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.registrationquiz.hook.RegistrationQuizFormField;
import cc.thonly.wikikernel.plugin.registrationquiz.hook.RegistrationQuizGuard;
import cc.thonly.wikikernel.plugin.registrationquiz.hook.RegistrationQuizMessages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

/**
 * 注册问答验证移植（对应 MediaWiki 的 QuestyCaptcha 思路）：
 * <ul>
 *     <li>{@link RegistrationQuizFormField}（{@code RegistrationFormField}）：注册页随机抽一道题，
 *     渲染题目 + 隐藏的题目下标 + 答案输入框；</li>
 *     <li>{@link RegistrationQuizGuard}（{@code RegistrationGuard}）：{@code POST /api/user/register}
 *     按下标校验答案（答错 {@code BLOCK} → 400 + 说明）；</li>
 *     <li>{@link RegistrationQuizMessages}（{@code MessageBundle}）：注册问答文案（键名不变）。</li>
 * </ul>
 * 抽题 / 校验逻辑**整体从 core 迁来**（{@link RegistrationQuizSettings}）；设置也归插件自己
 * （站点设置分区 + 安装向导步骤，键沿用迁移前的 {@code registration.questions} / {@code registration.answers}），
 * 插件经 {@link SiteConfig} 读同一批键。注册页的提交逻辑继续用 core 的 {@code /js/auth.js}
 * （它用 {@code FormData} 全量提交表单，不依赖具体字段，所以插件不需要自带脚本）。
 */
public class RegistrationQuizPlugin implements WikiPlugin {

    /** 功能开关键（与 core 的站点设置 / LocalSettings 一致）。 */
    public static final String ENABLED_KEY = RegistrationQuizSettings.ENABLED_KEY;

    /** 问题数组键（core 的站点设置与 {@code /init} 行编辑器写的就是它）。 */
    public static final String QUESTIONS_KEY = RegistrationQuizSettings.QUESTIONS_KEY;

    /** 答案数组键。 */
    public static final String ANSWERS_KEY = RegistrationQuizSettings.ANSWERS_KEY;

    /** 旧格式单问题键（兼容旧配置）。 */
    public static final String LEGACY_QUESTION_KEY = RegistrationQuizSettings.LEGACY_QUESTION_KEY;

    /** 站点设置读取桥（core 提供）。 */
    @Autowired
    private SiteConfig siteConfig;

    /** 本地化文案（core 的 MessageSource 已合并插件消息）。 */
    @Autowired
    private MessageSource messageSource;

    /** LocalSettings.yaml 的注册问答配置（与迁移前 core 同一批键）。 */
    @Autowired
    private RegistrationQuizYamlSettings yamlSettings;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("registration-quiz", "Registration Quiz", "1.0.0",
                "Registration quiz: two equal-length arrays (registration.questions / registration.answers) "
                        + "pick a random question and verify the answer by index");
    }

    @Override
    public void onStart(PluginContext context) {
        RegistrationQuizSettings settings = new RegistrationQuizSettings(siteConfig);
        RegistrationQuizSupport support = new RegistrationQuizSupport(messageSource);
        context.register(MessageBundle.class, new RegistrationQuizMessages());
        context.register(RegistrationFormField.class, new RegistrationQuizFormField(settings, support));
        context.register(RegistrationGuard.class, new RegistrationQuizGuard(settings, support));
        // 设置分区（站点设置页）与安装向导步骤：注册问答的设置属于插件自己，core 只负责渲染 / 保存
        context.register(SiteSettingsSection.class, new RegistrationQuizSettingsSection(messageSource));
        context.register(SetupStep.class, new RegistrationQuizSetupStep(messageSource));
        importYamlSettings();
        context.logger().log(System.Logger.Level.INFO, settings.enabled()
                ? "已启用注册问答验证：注册页随机抽一题（问题 " + settings.rows().size() + " 条）"
                + "，注册接口按下标校验答案"
                : "注册问答验证未启用（features.registration-quiz=false 或题库为空）：注册页不显示问题");
    }

    /** LocalSettings.yaml → 站点设置（与迁移前 core 的导入一致：写了的值每次启动覆盖一次，没写的键不动）。 */
    private void importYamlSettings() {
        if (siteConfig == null || yamlSettings == null) {
            return;
        }
        if (yamlSettings.getFeatures().getRegistrationQuiz() != null) {
            siteConfig.set(RegistrationQuizSettings.ENABLED_KEY,
                    yamlSettings.getFeatures().getRegistrationQuiz().toString());
        }
        if (!yamlSettings.getRegistration().getQuestions().isEmpty()) {
            siteConfig.set(RegistrationQuizSettings.QUESTIONS_KEY,
                    String.join("\n", yamlSettings.getRegistration().getQuestions()));
        }
        if (!yamlSettings.getRegistration().getAnswers().isEmpty()) {
            siteConfig.set(RegistrationQuizSettings.ANSWERS_KEY,
                    String.join("\n", yamlSettings.getRegistration().getAnswers()));
        }
        if (yamlSettings.getRegistration().getQuestion() != null) {
            siteConfig.set(RegistrationQuizSettings.LEGACY_QUESTION_KEY,
                    yamlSettings.getRegistration().getQuestion().trim());
        }
    }
}
