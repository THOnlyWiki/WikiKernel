package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 邮件类型扩展点（邮件类型注册中心）。
 * <p>
 * 新增邮件类型 = 新增一个 core Bean，或插件里 {@code PluginContext.register(EmailType.class, ...)}；
 * 展示名可通过消息键 {@code email.typeName.<id>} 本地化。
 * 是否真正发送由 LocalSettings 的 {@code wikikernel.email.enabled} 与
 * {@code wikikernel.email.types.<id>} 共同控制。
 */
public interface EmailType extends Extension {

    /** 类型 id（存库、配置与过滤用），如 confirm-email / password-reset。 */
    String id();

    /** 展示名（可本地化）；默认与 {@link #id()} 相同。 */
    default String displayName() {
        return id();
    }

    /** 未在配置里单独指定时，是否默认启用。 */
    default boolean enabledByDefault() {
        return true;
    }

    /** 排序（越小越靠前）。 */
    default int order() {
        return 100;
    }
}
