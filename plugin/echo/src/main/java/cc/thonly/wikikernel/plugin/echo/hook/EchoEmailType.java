package cc.thonly.wikikernel.plugin.echo.hook;

import cc.thonly.wikikernel.common.extension.EmailType;

/**
 * Echo 的邮件类型（{@code echo-notification}）：注册进 core 的邮件类型中心后，
 * 是否真正发送由 {@code wikikernel.email.enabled}（总开关）+ {@code wikikernel.email.types.echo-notification}
 * 共同控制；展示名走消息键 {@code email.typeName.echo-notification}。
 * <p>
 * 用户侧还有一层：偏好 {@code echo.pref.email=true}（默认关，opt-in，与 MediaWiki 的「通过电子邮件通知我」一致）。
 */
public final class EchoEmailType implements EmailType {

    /** 邮件类型 id（配置键 {@code wikikernel.email.types.echo-notification}）。 */
    public static final String ID = "echo-notification";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public int order() {
        return 200;
    }
}