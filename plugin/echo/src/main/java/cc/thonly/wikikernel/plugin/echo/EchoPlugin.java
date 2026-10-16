package cc.thonly.wikikernel.plugin.echo;

import cc.thonly.wikikernel.common.extension.CoreAopHook;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.extension.EmailType;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PersonalToolbarHook;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.plugin.echo.data.WikiNotification;
import cc.thonly.wikikernel.plugin.echo.hook.EchoHeadProvider;
import cc.thonly.wikikernel.plugin.echo.hook.EchoEditHook;
import cc.thonly.wikikernel.plugin.echo.hook.EchoEmailType;
import cc.thonly.wikikernel.plugin.echo.hook.EchoListeners;
import cc.thonly.wikikernel.plugin.echo.hook.EchoPreferenceSection;
import cc.thonly.wikikernel.plugin.echo.hook.EchoSettingsSection;
import cc.thonly.wikikernel.plugin.echo.hook.EchoToolbarHook;
import cc.thonly.wikikernel.plugin.echo.service.EchoNotificationStoreJdbc;
import cc.thonly.wikikernel.plugin.echo.service.EchoNotifier;
import cc.thonly.wikikernel.plugin.echo.service.EchoView;
import cc.thonly.wikikernel.plugin.echo.web.NotificationsSpecialPage;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * Echo 插件：**MediaWiki Echo 风格的用户通知**（通知中心 + 顶栏铃铛 + 通知偏好）。
 *
 * <h2>功能清单</h2>
 * <ul>
 *     <li><b>通知类型</b>：用户讨论页被编辑（{@code edit-user-talk}）、被 {@code [[用户:名]] / @名} 提及
 *     （{@code mention}）、编辑被回退（{@code revert}）、新用户欢迎（{@code welcome}）；</li>
 *     <li><b>顶栏铃铛</b>：{@code PersonalToolbarHook} 贡献条目（kawaii-timeless 的 {@code #personal-extra}、
 *     plain / vector / fallback 的个人工具区都会出现），带未读红点与下拉面板；</li>
 *     <li><b>特殊:通知</b>：{@code Special:Notifications}（别名 {@code Special:Notification} / {@code Special:Echo}），
 *     按类型过滤、翻页、单条 / 全部标为已读；</li>
 *     <li><b>参数设置 → 通知</b>：每类通知的开关（偏好键 {@code echo.pref.enable.<type>}）；</li>
 *     <li><b>站点设置 → Echo 通知</b>：站点级开关（讨论页 / 提及 / 回退 / 欢迎 通知分别可关）。</li>
 * </ul>
 *
 * <h2>触发方式（AOP，见描述文件 {@code plugin.aop=true}）</h2>
 * core 没有「编辑完成」事件，Echo 用 {@link CoreAopHook} 切核心 Bean：
 * {@code WikiPageService.editPage / editPageIfLatest}（保存后：讨论页 / 提及）、
 * {@code ActionApiService.handle}（{@code action=rollback} 成功后：被回退）、
 * {@code UserService.createUser}（注册成功：欢迎）。规则与去重在 {@link EchoNotifier}。
 *
 * <h2>数据</h2>
 * 表 {@code wiki_notification} 属于插件自己（{@link #schemaEntities()} 声明、core 的 Auto-DDL 建表），
 * 数据访问走 {@code common.service.PluginDatabase}（{@link EchoNotificationStoreJdbc}）；
 * 用户 / 页面信息走 {@code common} 的桥（{@code UserStore} / {@code WikiPageStore} 等），插件不碰 core 的 Mapper。
 */
public class EchoPlugin implements WikiPlugin {

    @Autowired
    private EchoNotificationStoreJdbc store;

    @Autowired
    private EchoNotifier notifier;

    @Autowired
    private EchoView view;

    @Autowired
    private EchoSupport support;

    @Autowired
    private EchoToolbarHook toolbar;

    @Autowired
    private EchoListeners listeners;

    @Autowired
    private EchoEditHook editHook;

    @Autowired
    private cc.thonly.wikikernel.plugin.echo.service.EchoMailer echoMailer;

    @Autowired
    private UserSettings userSettings;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("echo", "Echo", "1.0.0",
                "Notifications for talk page edits, mentions and rollbacks"
                        + " (Special:Notifications + header bell)");
    }

    /** 插件自己的一张表：{@code wiki_notification}（通知）。 */
    @Override
    public List<Class<?>> schemaEntities() {
        return List.of(WikiNotification.class);
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new EchoMessages());
        // 顶栏铃铛 + 下拉面板（全皮肤通用：走个人工具区）
        context.register(PersonalToolbarHook.class, toolbar);
        // 前端资源（样式 / 脚本 / 文案，站点级注入）
        context.register(HtmlHeadProvider.class, new EchoHeadProvider(support));
        // 特殊:通知
        context.register(SpecialPage.class, new NotificationsSpecialPage(store, view, support, toolbar, notifier));
        // 参数设置 → 通知（每类开关）
        context.register(PreferenceSection.class,
                new EchoPreferenceSection(userSettings, support, echoMailer));
        // 站点设置 → Echo 通知（站点级开关）
        context.register(SiteSettingsSection.class, new EchoSettingsSection(support));
        // 编辑路径的通知（讨论页 / 提及）：保存前钩子
        context.register(EditGuard.class, editHook);
        // 回退路径的通知：AOP 切 ActionApiService.handle（rollback）
        context.register(CoreAopHook.class, listeners);
        // 邮件通知：core 的邮件类型中心（wikikernel.email.types.echo-notification）
        context.register(EmailType.class, new EchoEmailType());
        // API（未读数 / 列表 / 标记已读 / 偏好）由 @RestController 提供，
        // core 的 PluginWebRegistrar 会挂到 MVC 上

        context.logger().log(System.Logger.Level.INFO, support.enabled()
                ? "已启用通知：讨论页被编辑 / @提及 / 被回退 / 欢迎，顶栏铃铛 + 特殊:通知"
                : "通知已关闭（features.echo=false）：不显示铃铛，接口提示不可用");
    }

    /** 插件停止：关闭邮件发送线程（避免线程泄漏 / 半途发送）。 */
    @Override
    public void onStop(PluginContext context) {
        if (echoMailer != null) {
            echoMailer.shutdown();
        }
    }
}