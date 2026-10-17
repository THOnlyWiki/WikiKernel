package cc.thonly.wikikernel.plugin.fingerprintlogin;

import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.plugin.fingerprintlogin.data.WikiFingerprint;
import cc.thonly.wikikernel.plugin.fingerprintlogin.hook.FingerprintLoginHeadProvider;
import cc.thonly.wikikernel.plugin.fingerprintlogin.hook.FingerprintLoginPreferenceSection;
import cc.thonly.wikikernel.plugin.fingerprintlogin.service.FingerprintLoginService;
import cc.thonly.wikikernel.plugin.fingerprintlogin.service.FingerprintStore;
import cc.thonly.wikikernel.common.service.LoginSession;
import cc.thonly.wikikernel.plugin.fingerprintlogin.service.FingerprintStoreJdbc;
import cc.thonly.wikikernel.plugin.fingerprintlogin.web.FingerprintLoginSpecialPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.List;

/**
 * 浏览器指纹登录（便利凭据：免密码登录）。
 * <p>
 * 功能：
 * <ul>
 *     <li><b>登录页</b>：注入的脚本在登录页 DOM 上插入「用浏览器指纹登录」按钮，
 *     点击后浏览器端采集稳定分量（UA / 语言 / 平台 / 硬件 / 屏幕 / 时区 / canvas / WebGL）
 *     拼成规范字符串，用 {@code crypto.subtle.digest('SHA-256')} 算出摘要，
 *     {@code POST /api/fingerprint-login} 命中则建立与密码登录一致的会话；</li>
 *     <li><b>管理</b>：参数设置的「指纹登录」分区与 {@code 特殊:指纹登录} 列出 / 添加 / 删除
 *     自己的指纹（每用户最多 {@link FingerprintStore#MAX_PER_USER} 个，只能删自己的）；</li>
 *     <li><b>开关</b>：core 的功能开关 {@code features.fingerprint-login}（**默认关闭**）：关闭时
 *     不注入脚本、接口返回禁用提示；</li>
 *     <li><b>限流</b>：core 的 {@code common.service.RateLimit} 桥按「IP + 指纹摘要」分类限流防爆破。</li>
 * </ul>
 * 数据访问全部走插件自己的实现（实体 {@code WikiFingerprint} 在插件内、表 {@code wiki_fingerprint} 由
 * {@code FingerprintLoginPlugin.schemaEntities()} 声明、core 的 Auto-DDL 建表，插件经
 * {@code common.service.PluginDatabase} 裸 SQL 读写；会话用 {@code LoginSessionImpl} 建立），
 * 限流仍走 core 的 {@code common.service.RateLimit} 桥。
 * <p>
 * 安全说明（也在插件 README 与站点设置里）：指纹是**比密码弱的便利凭据**——
 * 共享浏览器上的任何人都能直接登录，指纹也可能被复制到别的设备；
 * 因此有「每用户上限」「按 IP + 指纹限流」「可选仅 HTTPS」「可随时删除」这些基本防护。
 */
public class FingerprintLoginPlugin implements WikiPlugin {

    /** 插件 id。 */
    public static final String ID = "fingerprint-login";

    /** 功能开关键（与 core 站点设置 / LocalSettings 一致）。 */
    public static final String ENABLED_KEY = FingerprintLoginSupport.ENABLED_KEY;

    /** 插件子容器（core 的 PluginSpringContextFactory 创建；取 core 提供的桥 Bean 用）。 */
    @Autowired
    private ApplicationContext applicationContext;

    /** 功能开关 / 插件配置 / 文案（Spring Bean；{@code @RestController} 与各钩子共用）。 */
    @Autowired
    private FingerprintLoginSupport support;

    /** 业务核心（Spring Bean；{@code @RestController} 与管理界面共用）。 */
    @Autowired
    private FingerprintLoginService service;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(ID, "Browser Fingerprint Login", "1.0.0",
                "Passwordless sign-in with a browser-side SHA-256 device fingerprint"
                        + " (multiple fingerprints per account, self-service management)");
    }

    /**
     * 插件自己的表 {@code wiki_fingerprint}：core 在 {@code onStart} 之前按与 core 相同的
     * Auto-DDL 规则建表 / 补列（SQLite / MySQL / H2），数据访问见
     * {@link FingerprintStoreJdbc}（走 JDBC 桥）。
     */
    @Override
    public List<Class<?>> schemaEntities() {
        return List.of(WikiFingerprint.class);
    }

    @Override
    public void onStart(PluginContext context) {
        // Spring 子容器已经装配好这两个 Bean（onStart 在 refresh 之后调用）；
        // 独立运行 / 测试注入缺失时退化成安全默认值，绝不让插件启动失败。
        if (support == null) {
            support = new FingerprintLoginSupport(null, null, () -> null);
        }
        if (service == null) {
            service = new FingerprintLoginService(null, null, null, support);
        }
        context.register(MessageBundle.class, new FingerprintLoginMessages());
        context.register(HtmlHeadProvider.class, new FingerprintLoginHeadProvider(support));
        context.register(PreferenceSection.class, new FingerprintLoginPreferenceSection(service, support));
        context.register(SpecialPage.class, new FingerprintLoginSpecialPage(service, support));
        context.logger().log(System.Logger.Level.INFO, support.enabled()
                ? "已启用浏览器指纹登录：登录页按钮 + 参数设置 / 特殊:指纹登录 管理"
                + "（每用户最多 " + FingerprintStore.MAX_PER_USER + " 个，按 IP + 指纹限流）"
                : "浏览器指纹登录已关闭（features.fingerprint-login=false）：不注入按钮，接口返回禁用提示");
        if (support.enabled() && !bridgesReady()) {
            context.logger().log(System.Logger.Level.WARNING,
                    "指纹登录已启用，但 core 的数据桥未就绪（FingerprintStore / LoginSession / RateLimit）："
                            + "接口会返回错误而不是启动失败");
        }
    }

    /**
     * 数据桥是否都能从插件子容器取到（同为插件 Bean 的指纹存储 / 会话，加 core 提供的限流 / 当前用户）。
     * 只用于启动日志的健康检查：真实实例上应当为 true；取不到时插件降级，不影响 core 启动。
     */
    private boolean bridgesReady() {
        if (applicationContext == null) {
            // 没有 Spring 容器（独立运行 / 单元测试）：以业务 Bean 自身的判断为准
            return service != null && service.bridgesReady();
        }
        return beanOrNull(FingerprintStore.class) != null
                && beanOrNull(LoginSession.class) != null
                && beanOrNull(RateLimit.class) != null
                && beanOrNull(CurrentUser.class) != null;
    }

    /** 从插件子容器取一个 Bean；取不到返回 null。 */
    private <T> T beanOrNull(Class<T> type) {
        try {
            return applicationContext.getBean(type);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
