package cc.thonly.wikikernel.plugin.usermerge;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.plugin.usermerge.hook.UserMergeHeadProvider;
import cc.thonly.wikikernel.plugin.usermerge.hook.UserMergeLogType;
import cc.thonly.wikikernel.plugin.usermerge.service.UserMergeStore;
import cc.thonly.wikikernel.plugin.usermerge.web.UserMergeSpecialPage;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 用户合并移植（原 core 内置能力拆出，仿 MediaWiki 的 UserMerge 扩展）：
 * <ul>
 *     <li>特殊页 {@code 特殊:用户合并} / {@code Special:UserMerge} + {@code POST /api/user-merge}
 *     （{@link UserMergeSpecialPage} + 插件 {@code @RestController}）；</li>
 *     <li>前端脚本 {@code /plugins/user-merge/user-merge.js}（站点级注入，{@link UserMergeHeadProvider}）；</li>
 *     <li>日志类型 {@code usermerge}（{@link UserMergeLogType}，{@code 特殊:日志} 与最近更改照常显示）。</li>
 * </ul>
 * 迁移后的分工：core 不再认识「用户合并」这个名字，只保留「核查员」用户组（{@code PermissionService}）。
 * 桥 {@link UserMergeStore} 与数据访问都在插件里（{@code service.UserMergeStoreJdbc} 事务内用
 * {@code PluginDatabase} 裸 SQL 跨表重写编辑历史 / 日志 / 权限 / 文件 + 可选删除源账号 +
 * 写 {@code usermerge} 日志）。插件只做表单、接口、权限文案与结果映射。
 * <p>
 * 插件未加载时：{@code 特殊:用户合并} 与 {@code /api/user-merge} 不存在（404）、core 不写
 * {@code usermerge} 日志。
 * <p>
 * {@link UserMergeSupport}（{@code @Component}）与桥 {@code UserMergeStore} 由插件的 Spring 子容器注入，
 * 入口类直接 {@code @Autowired} 它们，钩子与 API 控制器共用同一实例。
 */
public class UserMergePlugin implements WikiPlugin {

    /** 插件 id（与 {@code META-INF/wiki-plugin.properties} 一致）。 */
    public static final String ID = "user-merge";

    @Autowired
    private UserMergeStore store;

    @Autowired
    private UserMergeSupport support;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(ID, "UserMerge", "1.0.0",
                "Merge one user account into another (Special:UserMerge + POST /api/user-merge)");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new UserMergeMessages());
        context.register(SpecialPage.class, new UserMergeSpecialPage(store, support));
        // 用户合并的日志类型（core 的 特殊:日志 与最近更改按注册中心识别；展示名走 log.typeName.usermerge）
        context.register(LogType.class, new UserMergeLogType(support));
        // 前端脚本（站点级注入）
        context.register(HtmlHeadProvider.class, new UserMergeHeadProvider(support));

        context.logger().log(System.Logger.Level.INFO,
                "已启用用户合并：特殊:用户合并 + POST /api/user-merge（需「核查员」权限，/plugins/user-merge/）");
    }
}