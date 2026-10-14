package cc.thonly.wikikernel.plugin.renameuser;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.plugin.renameuser.hook.RenameUserHeadProvider;
import cc.thonly.wikikernel.plugin.renameuser.hook.RenameUserLogType;
import cc.thonly.wikikernel.plugin.renameuser.service.UserAdminStore;
import cc.thonly.wikikernel.plugin.renameuser.web.RenameUserSpecialPage;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 重命名用户移植（原 core 内置能力拆出，仿 MediaWiki 的 Renameuser 扩展）：
 * <ul>
 *     <li>特殊页 {@code 特殊:重命名用户} / {@code Special:RenameUser} + {@code POST /api/user/rename}
 *     （{@link RenameUserSpecialPage} + 插件 {@code @RestController}）；</li>
 *     <li>前端脚本 {@code /plugins/rename-user/rename-user.js}（站点级注入，{@link RenameUserHeadProvider}）；</li>
 *     <li>日志类型 {@code renameuser}（{@link RenameUserLogType}，{@code 特殊:日志} 与最近更改照常显示）。</li>
 * </ul>
 * 迁移后的分工：core 不再认识「重命名用户」这个名字，只保留「重命名用户」用户组（{@code PermissionService}）。
 * 桥 {@link UserAdminStore} 与数据访问都在插件里（{@code service.UserAdminStoreJdbc} 用
 * {@code PluginDatabase} 裸 SQL 改名 + 用户名规则校验 + 写 {@code renameuser} 日志 + 可选移动用户页）。
 * 插件只做表单、接口、权限文案与结果映射。
 * <p>
 * 插件未加载时：{@code 特殊:重命名用户} 与 {@code /api/user/rename} 不存在（404）、core 不写
 * {@code renameuser} 日志。
 * <p>
 * {@link RenameUserSupport}（{@code @Component}）与桥 {@code UserAdminStore} 由插件的 Spring 子容器注入，
 * 入口类直接 {@code @Autowired} 它们，钩子与 API 控制器共用同一实例。
 */
public class RenameUserPlugin implements WikiPlugin {

    /** 插件 id（与 {@code META-INF/wiki-plugin.properties} 一致）。 */
    public static final String ID = "rename-user";

    @Autowired
    private UserAdminStore store;

    @Autowired
    private RenameUserSupport support;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(ID, "RenameUser", "1.0.0",
                "Rename a user account (Special:RenameUser + POST /api/user/rename)");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new RenameUserMessages());
        context.register(SpecialPage.class, new RenameUserSpecialPage(store, support));
        // 重命名用户的日志类型（core 的 特殊:日志 与最近更改按注册中心识别；展示名走 log.typeName.renameuser）
        context.register(LogType.class, new RenameUserLogType(support));
        // 前端脚本（站点级注入）
        context.register(HtmlHeadProvider.class, new RenameUserHeadProvider(support));

        context.logger().log(System.Logger.Level.INFO,
                "已启用重命名用户：特殊:重命名用户 + POST /api/user/rename（需「重命名用户」权限，/plugins/rename-user/）");
    }
}