package cc.thonly.wikikernel.plugin.externalauth.qq.mail;

import cc.thonly.wikikernel.common.extension.EmailType;

/** QQ 邮箱验证绑定的邮件类型（可在 {@code wikikernel.email.types.qq-email-bind} 单独关闭）。 */
public record QqEmailBindEmailType() implements EmailType {

    public static final String ID = "qq-email-bind";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "QQ email binding";
    }

    @Override
    public int order() {
        return 60;
    }
}
