package cc.thonly.wikikernel.common.externalauth;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 第三方登录头像导入桥（可选扩展点）。
 * <p>
 * core 在「第三方账号首次自动注册」成功后，如果 provider 声明
 * {@link ExternalAuthProvider#importAvatarOnRegister()}，会把 provider 返回的头像 URL
 * 交给已加载的桥实现。SocialProfile 插件实现本扩展点，把图片下载、校验并写入
 * {@code wiki_user.avatar}；没有加载 SocialProfile 时没有桥，导入自然跳过。
 * <p>
 * 实现必须自行保证：功能开关、URL / 内容校验、超时与失败不影响注册流程。
 */
public interface ExternalAuthAvatarBridge extends Extension {

    /**
     * 尝试导入一个外部头像。
     *
     * @param userId    刚注册的本地用户 ID
     * @param providerId 第三方 provider id（如 {@code github}）
     * @param avatarUrl  provider 返回的头像 URL
     * @return 是否已成功保存到本地头像存储
     */
    boolean importFromUrl(long userId, String providerId, String avatarUrl);
}
