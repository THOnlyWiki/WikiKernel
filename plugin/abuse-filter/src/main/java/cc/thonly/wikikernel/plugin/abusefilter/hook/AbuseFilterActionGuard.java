package cc.thonly.wikikernel.plugin.abusefilter.hook;

import cc.thonly.wikikernel.common.extension.ActionContext;
import cc.thonly.wikikernel.common.extension.ActionGuard;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterSupport;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterService;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseVariables;

import java.util.Map;

/**
 * 滥用过滤器的**通用动作钩子**（仿 MediaWiki 滥用过滤器对 move / upload / delete / protect / block
 * 这些动作的钩子）：core 在动作执行前调用，命中 {@code disallow} 时拒绝动作，命中 {@code warn} 时放行并提示。
 * <p>
 * 支持的动作为 {@code move} / {@code upload} / {@code delete} / {@code protect} / {@code block}
 * （core 目前接了移动、上传、删除文件、保护、封禁五处）；未识别的动作直接放行。
 * 注意：{@code tag} 操作只对**编辑**生效（core 的变更标签写在版本上），非编辑动作只记日志不写标签。
 */
public class AbuseFilterActionGuard implements ActionGuard {

    private final AbuseFilterService service;
    private final AbuseFilterSupport support;

    public AbuseFilterActionGuard(AbuseFilterService service, AbuseFilterSupport support) {
        this.service = service;
        this.support = support;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public Outcome beforeAction(ActionContext context) {
        if (!service.enabled()) {
            return Outcome.allow();
        }
        String action = Strings.trimToEmpty(context.action());
        Map<String, String> data = context.data();
        Map<String, Object> variables = switch (action) {
            case "move" -> AbuseVariables.move(data.getOrDefault("from", context.target()),
                    data.getOrDefault("to", ""));
            case "upload" -> AbuseVariables.upload(data.getOrDefault("name", context.target()),
                    parseLong(data.get("size")), data.getOrDefault("contentType", ""), context.summary());
            case "delete" -> AbuseVariables.deleteFile(data.getOrDefault("name", context.target()));
            case "protect" -> AbuseVariables.protect(data.getOrDefault("title", context.target()),
                    "true".equalsIgnoreCase(data.getOrDefault("protected", "")));
            case "block" -> AbuseVariables.block(data.getOrDefault("target", context.target()),
                    data.getOrDefault("reason", context.summary()));
            default -> null;
        };
        if (variables == null) {
            return Outcome.allow();
        }
        AbuseFilterService.Outcome outcome = service.evaluate(action, context.viewer(),
                context.clientIp(), variables);
        if (outcome.disallowed()) {
            return Outcome.block(support.disallowedText(outcome.messages()));
        }
        if (outcome.hasWarnings()) {
            return Outcome.warn(support.warnText(outcome.warnings()));
        }
        return Outcome.allow();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(Strings.trimToEmpty(value));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }
}
