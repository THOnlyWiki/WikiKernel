package cc.thonly.wikikernel.mail;

import cc.thonly.wikikernel.common.extension.EmailType;

import java.util.List;
import java.util.Optional;

/**
 * 邮件类型注册中心：合并 core Bean 与插件注册的 {@link EmailType}（core 优先），
 * 并提供本地化展示名（消息键 {@code email.typeName.<id>}）。
 */
public interface EmailTypeRegistry {

    /** 全部邮件类型（按 order 排序）。 */
    List<EmailType> all();

    /** 按 id 查类型。 */
    Optional<EmailType> byId(String id);

    /** 类型的本地化展示名；未知类型回退 id。 */
    String displayNameOf(String id);
}
