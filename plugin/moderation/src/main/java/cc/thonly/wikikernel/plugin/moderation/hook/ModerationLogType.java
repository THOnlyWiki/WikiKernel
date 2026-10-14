package cc.thonly.wikikernel.plugin.moderation.hook;

import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.moderation.ModerationMessages;
import cc.thonly.wikikernel.plugin.moderation.ModerationSupport;

/**
 * 审核日志类型（{@code moderation} = 通过 / {@code moderationreject} = 拒绝；仿 MediaWiki Moderation）。
 * <p>
 * 迁移前这两个类型在 core 的 {@code LogTypeConfig} 里注册，现在由插件注册
 * （{@code 特殊:日志} 的类型下拉框、最近更改的日志名都按注册中心识别；
 * 展示名走消息键 {@code log.typeName.<id>}，文案在插件 {@link ModerationMessages} 里，键名不变）。
 * <p>
 * 日志详情是 {@code <变更类型>|<审核意见>}，展示文案由 {@link #detailText(String)} 本地化
 * （core 的 {@code LogFormatter} 优先问类型自己，见 {@code LogType.detailText}）。
 */
public record ModerationLogType(String id, String displayName, ModerationSupport support) implements LogType {

    /** 通过待审变更。 */
    public static final String ID_APPROVE = "moderation";
    /** 拒绝待审变更。 */
    public static final String ID_REJECT = "moderationreject";

    @Override
    public TargetKind targetKind() {
        return TargetKind.PAGE;
    }

    @Override
    public int order() {
        return ID_APPROVE.equals(id) ? 75 : 76;
    }

    /** 详情：{@code 页面编辑|意见} → 「页面编辑（意见：…）」。 */
    @Override
    public String detailText(String detail) {
        String[] parts = Strings.trimToEmpty(detail).split("\\|", -1);
        String changeType = parts.length > 0 ? parts[0] : "";
        String comment = parts.length > 1 ? parts[1] : "";
        String text = support.message("log.moderation.change." + changeType, changeType);
        if (comment.isEmpty()) {
            return text;
        }
        return text + support.message("log.moderation.comment", null, comment);
    }
}
