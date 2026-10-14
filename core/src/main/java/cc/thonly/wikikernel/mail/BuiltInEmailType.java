package cc.thonly.wikikernel.mail;

import cc.thonly.wikikernel.common.extension.EmailType;

/** 内置邮件类型（id / 排序）；展示名走消息键 {@code email.typeName.<id>}。 */
public record BuiltInEmailType(String id, int order) implements EmailType {
}
