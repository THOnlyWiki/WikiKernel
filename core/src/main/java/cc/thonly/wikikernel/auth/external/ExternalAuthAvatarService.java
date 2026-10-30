package cc.thonly.wikikernel.auth.external;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthAvatarBridge;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 第三方注册后的头像导入调度：
 * provider 明确声明 {@link ExternalAuthProvider#importAvatarOnRegister()} 且返回头像 URL 时，
 * 依次询问已加载的 {@link ExternalAuthAvatarBridge}（例如 SocialProfile）。
 * <p>
 * 没有桥 / 功能关闭 / 下载失败都只记录日志，绝不影响注册与登录。
 */
@Service
public class ExternalAuthAvatarService {

    private static final Logger log = LoggerFactory.getLogger(ExternalAuthAvatarService.class);

    private final PluginManager pluginManager;

    public ExternalAuthAvatarService(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /** 首次自动注册成功之后调用（绑定已有账号不调用）。 */
    public void importAfterRegistration(ExternalAuthProvider provider, long userId,
                                        ExternalAuthUser profile) {
        if (provider == null || userId <= 0 || profile == null
                || !provider.importAvatarOnRegister()
                || Strings.isBlank(profile.avatarUrl())) {
            return;
        }
        String avatarUrl = Strings.trimToEmpty(profile.avatarUrl());
        for (ExternalAuthAvatarBridge bridge : pluginManager.extensions(ExternalAuthAvatarBridge.class)) {
            try {
                if (bridge.importFromUrl(userId, provider.id(), avatarUrl)) {
                    log.info("已导入第三方头像：provider={} userId={}", provider.id(), userId);
                    return;
                }
            } catch (RuntimeException exception) {
                log.warn("第三方头像导入失败（已跳过）：provider={} userId={} bridge={} error={}",
                        provider.id(), userId, bridge.getClass().getName(), exception.toString());
            }
        }
    }
}
