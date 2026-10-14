package cc.thonly.wikikernel.plugin.moderation.hook;

import cc.thonly.wikikernel.common.extension.ActionContext;
import cc.thonly.wikikernel.common.extension.ActionGuard;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.moderation.ModerationSupport;
import cc.thonly.wikikernel.plugin.moderation.service.ModerationService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审核的**通用动作钩子**（文件上传 / 文件删除）：
 * <ul>
 *     <li>{@code upload}：文件内容（{@link ActionContext#payload()}）**暂存**到
 *     {@code {run-path}/pending/} 并进队列，core 不写正式存储；返回 {@code HANDLED} +
 *     {@code pending=true}（网页上传前端据此不跳文件页、批量上传据此计入「待审核」）+
 *     {@code moderated=true}（{@code api.php?action=upload} 的响应字段，与迁移前一致）；</li>
 *     <li>{@code delete}：删除请求进队列，core 不执行删除。</li>
 * </ul>
 * 开关关闭 / 未登录 / 豁免组 / 不是这两个动作时一律放行（行为与没有插件时一致）；
 * 拿不到文件内容（例如未来的其它上传入口没带载荷）时也放行，不会把文件「吃掉」。
 * 移动页面**不审核**（迁移前 core 也没有 move 审核，见插件 README 的差异说明）。
 */
public class ModerationActionGuard implements ActionGuard {

    private final ModerationService service;
    private final ModerationSupport support;

    public ModerationActionGuard(ModerationService service, ModerationSupport support) {
        this.service = service;
        this.support = support;
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public Outcome beforeAction(ActionContext context) {
        if (!service.enabled() || !service.shouldModerate(context.viewer())) {
            return Outcome.allow();
        }
        String action = Strings.trimToEmpty(context.action());
        return switch (action) {
            case "upload" -> upload(context);
            case "delete" -> delete(context);
            default -> Outcome.allow();
        };
    }

    private Outcome upload(ActionContext context) {
        byte[] payload = context.payload();
        if (payload == null || payload.length == 0) {
            // 没有载荷就没有东西可暂存：放行让 core 按原样处理，而不是把上传悄悄吞掉
            return Outcome.allow();
        }
        String fileName = Strings.trimToEmpty(context.data().get("name"));
        if (fileName.isEmpty()) {
            fileName = Strings.trimToEmpty(context.target());
        }
        service.submitUpload(context.viewer(), fileName, context.summary(), payload);
        Map<String, Object> data = new LinkedHashMap<>();
        // 网页上传：前端看到 pending 就不跳文件页 / 计入「待审核」；api.php：返回 moderated=true
        data.put("pending", true);
        data.put("name", fileName);
        data.put("moderated", true);
        return Outcome.handled(support.message("files.uploadPending"), data);
    }

    private Outcome delete(ActionContext context) {
        long fileId = parseLong(Strings.trimToEmpty(context.data().get("id")));
        if (fileId <= 0) {
            return Outcome.allow();
        }
        String fileName = Strings.trimToEmpty(context.data().get("name"));
        boolean hard = "true".equalsIgnoreCase(context.data().getOrDefault("hard", "false"));
        service.submitFileDelete(context.viewer(), fileId, fileName, hard, context.summary());
        return Outcome.handled(support.message("files.deletePending"));
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }
}
