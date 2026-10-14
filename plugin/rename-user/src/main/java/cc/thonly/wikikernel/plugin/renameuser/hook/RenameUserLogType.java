package cc.thonly.wikikernel.plugin.renameuser.hook;

import cc.thonly.wikikernel.common.extension.LogType;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.renameuser.RenameUserMessages;
import cc.thonly.wikikernel.plugin.renameuser.RenameUserSupport;

/**
 * 重命名用户日志类型（{@code renameuser}；仿 MediaWiki Renameuser 的日志）。
 * <p>
 * 迁移前这个类型在 core 的 {@code LogTypeConfig} 里注册，现在由插件注册
 * （{@code 特殊:日志} 的类型下拉框、最近更改的日志名都按注册中心识别；
 * 展示名走消息键 {@code log.typeName.renameuser}，文案在插件 {@link RenameUserMessages} 里，键名不变）。
 * <p>
 * 日志详情由插件的 {@code service.UserAdminStoreJdbc} 写成 {@code rename|<新名>|<原因>}，
 * 展示文案由 {@link #detailText(String)} 本地化（core 的 {@code LogFormatter} 优先问类型自己，
 * 见 {@code LogType.detailText}）。
 */
public record RenameUserLogType(RenameUserSupport support) implements LogType {

    @Override
    public String id() {
        return "renameuser";
    }

    @Override
    public TargetKind targetKind() {
        return TargetKind.USER;
    }

    @Override
    public int order() {
        return 55;
    }

    /** 详情：{@code rename|新名|原因} → 「新用户名：新名，原因：…」。 */
    @Override
    public String detailText(String detail) {
        String[] parts = Strings.trimToEmpty(detail).split("\\|", -1);
        String newName = parts.length > 1 ? parts[1] : "";
        String reason = parts.length > 2 ? parts[2] : "";
        String text = support.message("log.renameuser.newName", null, newName);
        if (Strings.isBlank(reason)) {
            return text;
        }
        return text + support.message("log.renameuser.reason", null, reason);
    }
}