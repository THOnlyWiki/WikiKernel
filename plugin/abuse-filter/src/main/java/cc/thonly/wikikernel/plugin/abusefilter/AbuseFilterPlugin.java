package cc.thonly.wikikernel.plugin.abusefilter;

import cc.thonly.wikikernel.common.extension.ActionGuard;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.RegistrationGuard;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilter;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilterRevision;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseLog;
import cc.thonly.wikikernel.plugin.abusefilter.hook.AbuseFilterActionGuard;
import cc.thonly.wikikernel.plugin.abusefilter.hook.AbuseFilterEditGuard;
import cc.thonly.wikikernel.plugin.abusefilter.hook.AbuseFilterHeadProvider;
import cc.thonly.wikikernel.plugin.abusefilter.hook.AbuseFilterRegistrationGuard;
import cc.thonly.wikikernel.plugin.abusefilter.model.AbuseContext;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseCondition;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterService;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseJson;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseValues;
import cc.thonly.wikikernel.plugin.abusefilter.web.AbuseFilterApiController;
import cc.thonly.wikikernel.plugin.abusefilter.web.AbuseFilterSpecialPage;
import cc.thonly.wikikernel.plugin.abusefilter.web.AbuseLogSpecialPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 滥用过滤器移植（原 core 内置能力拆出，仿 MediaWiki 的 AbuseFilter 扩展）：
 * <ul>
 *     <li>条件求值器（{@link AbuseCondition} / {@link AbuseContext} / {@link AbuseValues} / {@link AbuseJson}）；</li>
 *     <li>过滤器管理（{@link AbuseFilterService} + {@link AbuseFilterSpecialPage} + {@link AbuseFilterApiController}）；</li>
 *     <li>滥用日志（{@link AbuseLogSpecialPage}，自己的表 {@code wiki_abuse_log}）；</li>
 *     <li>评估入口：{@link EditGuard}（编辑 / 创建 / API / AI）、{@link ActionGuard}
 *     （移动 / 上传 / 删除文件 / 保护 / 封禁）、{@link RegistrationGuard}（注册）；</li>
 *     <li>系统账号「滥用过滤器」（机器人）、前端脚本与样式、全部文案。</li>
 * </ul>
 * 迁移后的分工：core 不再认识「滥用过滤器」这个名字，只保留功能开关（{@code features.abuse-filter}，
 * 默认关闭）与站点设置 / 安装向导里的复选框（说明改为「系统账号由插件创建」）；
 * 数据仍由 core 的 Auto-DDL 建表（{@code common.data} 的三个实体 + core 的 Mapper），
 * 插件通过 {@code common.service.AbuseFilterStore} / {@code UserWriter} / {@code BlockWriter} 等桥读写。
 * <p>
 * 插件未加载时：编辑 / 移动 / 注册 / 上传 / 删除 / 封禁照常（没有过滤器评估）、
 * {@code 特殊:滥用过滤器} 与 {@code 特殊:滥用日志} 不存在、{@code /api/abuse-filter*} 接口不存在、
 * core 不写滥用日志。
 */
public class AbuseFilterPlugin implements WikiPlugin {

    /** 插件 id（与 {@code META-INF/wiki-plugin.properties} 一致）。 */
    public static final String ID = "abuse-filter";

    /** 功能开关键（与 core 站点设置 / LocalSettings 一致）：{@code features.abuse-filter}。 */
    public static final String ENABLED_KEY = AbuseFilterSupport.ENABLED_KEY;

    @Autowired
    private AbuseFilterService service;

    @Autowired
    private AbuseFilterSupport support;

    @Autowired
    private UserStore userStore;

    @Autowired
    private WikiPageStore wikiPageStore;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(ID, "AbuseFilter", "1.0.0",
                "Condition-expression abuse filter with block / warn / tag / throttle / block actions"
                        + " and an abuse log (Special:AbuseFilter + Special:AbuseLog)");
    }

    /**
     * 插件自己的三张表（{@code wiki_abuse_filter} / {@code wiki_abuse_filter_revision} /
     * {@code wiki_abuse_log}）：core 在 {@code onStart} 之前按与 core 相同的 Auto-DDL 规则建表 / 补列
     * （SQLite / MySQL / H2），数据访问见 {@code service/AbuseFilterStoreJdbc}（走 JDBC 桥）。
     */
    @Override
    public List<Class<?>> schemaEntities() {
        return List.of(WikiAbuseFilter.class, WikiAbuseFilterRevision.class, WikiAbuseLog.class);
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new AbuseFilterMessages());
        context.register(SpecialPage.class, new AbuseFilterSpecialPage(service, support, userStore));
        context.register(SpecialPage.class, new AbuseLogSpecialPage(service, support, userStore));
        context.register(HtmlHeadProvider.class, new AbuseFilterHeadProvider(support));
        // 评估入口：编辑 / 创建（web / api / ai）、移动 / 上传 / 删除 / 保护 / 封禁、注册
        context.register(EditGuard.class, new AbuseFilterEditGuard(service, support, wikiPageStore));
        context.register(ActionGuard.class, new AbuseFilterActionGuard(service, support));
        context.register(RegistrationGuard.class, new AbuseFilterRegistrationGuard(service, support));

        boolean enabled = support.enabled();
        if (enabled) {
            // 启用状态下启动时创建系统账号「滥用过滤器」（机器人）；已存在则不动它
            boolean created = service.ensureSystemAccount();
            context.logger().log(System.Logger.Level.INFO, created
                    ? "滥用过滤器已启用：已创建系统账号「滥用过滤器」（机器人）"
                    : "滥用过滤器已启用（系统账号「滥用过滤器」已存在）");
        } else {
            context.logger().log(System.Logger.Level.INFO,
                    "滥用过滤器已关闭（features.abuse-filter=false）：不评估任何过滤器，不写滥用日志");
        }
    }
}
