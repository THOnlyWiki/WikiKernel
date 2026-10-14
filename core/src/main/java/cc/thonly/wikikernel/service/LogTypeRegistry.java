package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.extension.LogType;

import java.util.List;
import java.util.Optional;

/**
 * 日志类型注册中心：合并 core Bean 与插件注册的 {@link LogType}（core 优先），
 * 并提供本地化展示名（消息键 {@code log.typeName.<id>}）。
 */
public interface LogTypeRegistry {

    /** 全部日志类型（按 order 排序）。 */
    List<LogType> all();

    /** 按 id 查类型。 */
    Optional<LogType> byId(String id);

    /** 类型的本地化展示名；未知类型回退 id。 */
    String displayNameOf(String id);
}
