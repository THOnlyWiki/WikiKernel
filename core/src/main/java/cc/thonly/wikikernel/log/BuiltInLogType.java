package cc.thonly.wikikernel.log;

import cc.thonly.wikikernel.common.extension.LogType;

/** 内置日志类型（id / 目标类型 / 排序）。展示名走消息键 {@code log.typeName.<id>}。 */
public record BuiltInLogType(String id, TargetKind targetKind, int order) implements LogType {
}
