package cc.thonly.wikikernel.plugin.abusefilter.hook;

import cc.thonly.wikikernel.common.extension.RegistrationContext;
import cc.thonly.wikikernel.common.extension.RegistrationGuard;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterSupport;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterService;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseVariables;

/**
 * 滥用过滤器的**注册前钩子**（仿 MediaWiki 的 {@code AbortNewAccount} / {@code action=createaccount}）：
 * 命中 {@code disallow} 时注册接口返回 400 + 说明。
 * <p>
 * 注册页没有「再次点击提交」的二次确认通道，所以 {@code warn} / {@code degrade} 只写日志、不拦注册
 * （与迁移前一致：迁移前的注册路径同样只检查 {@code disallowed}）。
 */
public class AbuseFilterRegistrationGuard implements RegistrationGuard {

    private final AbuseFilterService service;
    private final AbuseFilterSupport support;

    public AbuseFilterRegistrationGuard(AbuseFilterService service, AbuseFilterSupport support) {
        this.service = service;
        this.support = support;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public Outcome beforeRegister(RegistrationContext context) {
        if (!service.enabled()) {
            return Outcome.allow();
        }
        // 注册者还没有账号：变量里的 user_* 按匿名处理（与迁移前 checkCreateAccount 一致）
        AbuseFilterService.Outcome outcome = service.evaluate("createaccount", Viewer.ANONYMOUS,
                context.clientIp(), AbuseVariables.createAccount(context.username()));
        if (outcome.disallowed()) {
            return Outcome.block(support.disallowedText(outcome.messages()));
        }
        return Outcome.allow();
    }
}
