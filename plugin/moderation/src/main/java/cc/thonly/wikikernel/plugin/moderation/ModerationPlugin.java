package cc.thonly.wikikernel.plugin.moderation;

import cc.thonly.wikikernel.common.extension.ActionGuard;
import cc.thonly.wikikernel.common.extension.EditFormField;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiDiff;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.plugin.moderation.data.WikiPendingChange;
import cc.thonly.wikikernel.plugin.moderation.hook.ModerationActionGuard;
import cc.thonly.wikikernel.plugin.moderation.hook.ModerationEditFormField;
import cc.thonly.wikikernel.plugin.moderation.hook.ModerationEditGuard;
import cc.thonly.wikikernel.plugin.moderation.hook.ModerationHeadProvider;
import cc.thonly.wikikernel.plugin.moderation.hook.ModerationLogType;
import cc.thonly.wikikernel.plugin.moderation.hook.ModerationPageDisplayHook;
import cc.thonly.wikikernel.plugin.moderation.service.ModerationService;
import cc.thonly.wikikernel.plugin.moderation.web.ModerationApiController;
import cc.thonly.wikikernel.plugin.moderation.web.ModerationSpecialPage;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * 审核移植（原 core 内置能力拆出，仿 MediaWiki 的 Moderation 扩展）：
 * <ul>
 *     <li>队列服务（{@link ModerationService} + {@link ModerationSpecialPage} + {@link ModerationApiController}）；</li>
 *     <li>入口钩子：编辑 / 创建 → {@link EditGuard}（写进队列，{@code HANDLED} + 「已提交审核」跳转）、
 *     上传 / 删除 → {@link ActionGuard}（上传先暂存、删除进队列）、编辑页提示 → {@link EditFormField}、
 *     页面提示条与「待审创建」页 → {@link PageDisplayHook}；</li>
 *     <li>日志类型 {@code moderation} / {@code moderationreject}（{@link LogType} 注册中心）、
 *     前端脚本与样式、全部文案。</li>
 * </ul>
 * 迁移后的分工：core 不再认识「审核」这个名字，只保留站点设置开关（{@code features.moderation}，
 * 默认关闭）与安装向导 / 站点设置里的复选框；表 {@code wiki_pending_change} 与实体
 * {@link WikiPendingChange} **属于插件自己**：插件用 {@code schemaEntities()} 声明、
 * core 的 Auto-DDL 建表 / 补列，数据访问走 {@code common.service.PluginDatabase}
 * （见 {@link ModerationStoreJdbc}）；待审上传文件暂存在插件数据目录下的 {@code pending/}
 * （见 {@link PendingFileStoreImpl}），应用变更仍走 {@code PageWriter} / {@code FileWriter} /
 * {@code WikiDiff} / {@code LogWriter} 等平台桥。
 * <p>
 * 插件未加载时：普通用户的编辑 / 上传 / 删除**立即生效**（没有队列）、{@code 特殊:审核} 与
 * {@code /api/moderation/*} 不存在、页面没有待审提示条、编辑页没有「进入审核队列」提示。
 * <p>
 * {@link ModerationService}（{@code @Service}）与 {@link ModerationSupport}（{@code @Component}）由插件的
 * Spring 子容器创建（注入 core 的桥），入口类直接 {@code @Autowired} 它们，钩子与 API 控制器共用同一实例。
 */
public class ModerationPlugin implements WikiPlugin {

    /** 插件 id（与 {@code META-INF/wiki-plugin.properties} 一致）。 */
    public static final String ID = "moderation";

    /** 功能开关键（与 core 站点设置 / LocalSettings 一致）：{@code features.moderation}。 */
    public static final String ENABLED_KEY = ModerationSupport.ENABLED_KEY;

    @Autowired
    private ModerationService service;

    @Autowired
    private ModerationSupport support;

    @Autowired
    private WikiPageStore wikiPageStore;

    @Autowired
    private WikiDiff wikiDiff;

    @Autowired
    private WikiText wikiText;

    @Autowired
    private UserStore userStore;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(ID, "Moderation", "1.0.0",
                "Review queue for page edits, file uploads and file deletions"
                        + " (Special:Moderation + /api/moderation/*)");
    }

    /**
     * 插件自己的表 {@code wiki_pending_change}：core 在 {@code onStart} 之前按与 core 相同的
     * Auto-DDL 规则建表 / 补列（SQLite / MySQL / H2），数据访问见
     * {@link cc.thonly.wikikernel.plugin.moderation.service.ModerationStoreJdbc}（走 JDBC 桥）。
     */
    @Override
    public List<Class<?>> schemaEntities() {
        return List.of(WikiPendingChange.class);
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new ModerationMessages());
        context.register(SpecialPage.class, new ModerationSpecialPage(service, support, wikiPageStore,
                wikiDiff, userStore));
        // 审核动作的日志类型（core 的 特殊:日志 与最近更改按注册中心识别；展示名走 log.typeName.<id>）
        context.register(LogType.class,
                new ModerationLogType(ModerationLogType.ID_APPROVE, ModerationLogType.ID_APPROVE, support));
        context.register(LogType.class,
                new ModerationLogType(ModerationLogType.ID_REJECT, ModerationLogType.ID_REJECT, support));
        // 入口：编辑 / 创建排队、上传 / 删除排队、编辑页提示、页面提示条与「待审创建」页
        context.register(EditGuard.class, new ModerationEditGuard(service, support, wikiPageStore));
        context.register(ActionGuard.class, new ModerationActionGuard(service, support));
        context.register(EditFormField.class, new ModerationEditFormField(service, support));
        context.register(PageDisplayHook.class, new ModerationPageDisplayHook(service, support, wikiText));
        context.register(HtmlHeadProvider.class, new ModerationHeadProvider(support));

        context.logger().log(System.Logger.Level.INFO, support.enabled()
                ? "已启用审核：没有豁免权的用户提交的编辑 / 上传 / 删除先进 特殊:审核 队列（/plugins/moderation/）"
                : "审核已关闭（features.moderation=false）：编辑 / 上传 / 删除直接生效，不进队列");
    }
}
