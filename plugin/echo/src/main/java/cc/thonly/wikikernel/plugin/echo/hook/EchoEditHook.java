package cc.thonly.wikikernel.plugin.echo.hook;

import cc.thonly.wikikernel.common.extension.EditContext;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.echo.EchoSupport;
import cc.thonly.wikikernel.plugin.echo.service.EchoNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Echo 的通知触发（编辑路径）：**保存前钩子** {@link EditGuard}（网页编辑表单 / Action API / AI 提交都会走），
 * 在保存之前按本插件规则登记通知：
 * <ul>
 *     <li>用户讨论页被编辑（{@code edit-user-talk}）：收件人是讨论页主人；</li>
 *     <li>正文里的 {@code [[用户:名]] / @名} 提及（{@code mention}）：逐人通知。</li>
 * </ul>
 * <p>
 * 为什么用 {@link EditGuard} 而不是 AOP 切 {@code WikiPageService.editPage}：core 的插件 AOP 桥
 * 只代理**非** AOP 代理的核心 Bean（{@code @Transactional} 的服务会被跳过，见
 * {@code PluginAopBridge#eligible}），{@code WikiPageService} 的实现类正是 {@code @Transactional}。
 * {@link EditGuard} 在事务之外调用（core 在建事务之前收集钩子结论），通知写库不会与编辑事务抢 SQLite 写锁。
 * <p>
 * 代价：通知在**保存前**登记，若保存随后被其它钩子/乐观锁拦下会留下一条「幽灵通知」——
 * 已通过「同类同页 60 秒去重 + 自通知过滤」把影响压到最小（回退通知走 AOP，见 {@code EchoListeners}）。
 */
@Component
public final class EchoEditHook implements EditGuard {

    private static final Logger log = LoggerFactory.getLogger(EchoEditHook.class);

    /** 排在其它钩子（验证码 / 审核 / 滥用过滤器）之后：先让别人拦，再登记通知。 */
    private static final int ORDER = 800;

    private final EchoNotifier notifier;
    private final EchoSupport support;

    public EchoEditHook(EchoNotifier notifier, EchoSupport support) {
        this.notifier = notifier;
        this.support = support;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Outcome beforeSave(EditContext context) {
        if (context == null || !support.enabled()) {
            return Outcome.allow();
        }
        Viewer viewer = context.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return Outcome.allow(); // 匿名编辑不通知（没有可归属的触发者）
        }
        String title = context.title();
        if (title == null || title.isBlank()) {
            return Outcome.allow();
        }
        try {
            if (!support.disableTalk() && EchoSupport.isUserTalk(title)) {
                notifier.talkPageEdited(viewer.id(), title, null, context.summary());
            }
            if (!support.disableMention()) {
                notifier.mention(viewer.id(), title, null, context.content());
            }
        } catch (RuntimeException exception) {
            // 通知失败绝不影响编辑
            log.warn("[echo] 保存前登记通知失败（不影响编辑）：{}", exception.toString());
        }
        return Outcome.allow();
    }
}