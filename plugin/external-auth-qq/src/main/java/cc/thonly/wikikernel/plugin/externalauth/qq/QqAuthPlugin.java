package cc.thonly.wikikernel.plugin.externalauth.qq;

import cc.thonly.wikikernel.common.extension.EmailType;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.externalauth.qq.data.QqEmailBinding;
import cc.thonly.wikikernel.plugin.externalauth.qq.mail.QqEmailBindEmailType;
import cc.thonly.wikikernel.plugin.externalauth.qq.service.QqEmailBindingService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/** QQ 第三方登录（可选 OAuth）与 QQ 邮箱验证绑定。 */
public class QqAuthPlugin implements WikiPlugin {

    @Autowired
    private SiteConfig siteConfig;

    @Autowired
    private QqEmailBindingService qqEmailBindings;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("external-auth-qq", "QQ", "1.1.0",
                "QQ email verification binding (optional QQ OAuth login)");
    }

    @Override
    public List<Class<?>> schemaEntities() {
        return List.of(QqEmailBinding.class);
    }

    @Override
    public void onStart(PluginContext context) {
        // 保留原有 QQ OAuth 登录；QQ 邮箱绑定是独立、无需 OAuth App 的验证流程。
        context.register(ExternalAuthProvider.class, new QqAuthProvider(siteConfig));
        context.register(MessageBundle.class, new QqEmailMessages());
        context.register(EmailType.class, new QqEmailBindEmailType());
        context.register(PreferenceSection.class, new QqEmailPreferenceSection(qqEmailBindings));
        context.logger().log(System.Logger.Level.INFO,
                "已注册 QQ 第三方登录与 QQ 邮箱验证绑定（参数设置 → QQ 邮箱绑定）");
    }
}
