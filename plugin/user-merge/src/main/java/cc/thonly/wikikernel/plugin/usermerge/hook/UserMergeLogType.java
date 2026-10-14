package cc.thonly.wikikernel.plugin.usermerge.hook;

import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.usermerge.UserMergeMessages;
import cc.thonly.wikikernel.plugin.usermerge.UserMergeSupport;

/**
 * 用户合并日志类型（{@code usermerge}；仿 MediaWiki UserMerge 的日志）。
 * <p>
 * 迁移前这个类型在 core 的 {@code LogTypeConfig} 里注册，现在由插件注册
 * （{@code 特殊:日志} 的类型下拉框、最近更改的日志名都按注册中心识别；
 * 展示名走消息键 {@code log.typeName.usermerge}，文案在插件 {@link UserMergeMessages} 里，键名不变）。
 * <p>
 * 日志详情由插件的 {@code service.UserMergeStoreJdbc} 写成 {@code merge|<源名>|<deleted|kept>}，
 * 展示文案由 {@link #detailText(String)} 本地化（core 的 {@code LogFormatter} 优先问类型自己，
 * 见 {@code LogType.detailText}）。
 */
public record UserMergeLogType(UserMergeSupport support) implements LogType {

    @Override
    public String id() {
        return "usermerge";
    }

    @Override
    public TargetKind targetKind() {
        return TargetKind.USER;
    }

    @Override
    public int order() {
        return 56;
    }

    /** 详情：{@code merge|源名|deleted|kept} → 「源账号：源名（已删除 / 已保留）」。 */
    @Override
    public String detailText(String detail) {
        String[] parts = Strings.trimToEmpty(detail).split("\\|", -1);
        String source = parts.length > 1 ? parts[1] : "";
        String status = parts.length > 2 ? parts[2] : "";
        String text = support.message("log.usermerge.source", null, source);
        if (Strings.isBlank(status)) {
            return text;
        }
        return text + support.message("log.usermerge." + status, status);
    }
}