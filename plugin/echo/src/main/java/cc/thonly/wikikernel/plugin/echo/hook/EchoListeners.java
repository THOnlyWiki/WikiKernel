package cc.thonly.wikikernel.plugin.echo.hook;

import cc.thonly.wikikernel.common.extension.CoreAopHook;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.echo.EchoSupport;
import cc.thonly.wikikernel.plugin.echo.service.EchoNotifier;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Echo 的通知触发（回退路径）：**回退（rollback）不会走 {@code EditGuard}**
 * （{@code ActionApiService.rollback} 直接调 {@code WikiPageService.editPage}），
 * 所以这里用 {@link CoreAopHook} 切 {@code ActionApiService.handle}：
 * <ul>
 *     <li>调用前：识别 {@code action=rollback}，按参数（{@code title} / {@code user} / {@code userid}）
 *     查「该用户对本页的连续编辑者」（与 core 的 rollback 判定一致）——此时页面历史还是回退前的状态；</li>
 *     <li>调用后（成功）：给这些被回退的用户发 {@code revert} 通知。</li>
 * </ul>
 * <p>
 * 为什么能切 {@code ActionApiService}：它不是 {@code @Transactional} Bean（回退里的写事务在
 * {@code WikiPageService.editPage} 自己的调用里），core 的插件 AOP 桥会为它套桥接代理。
 * 编辑路径（讨论页 / 提及）不能用 AOP（{@code WikiPageService} 是 {@code @Transactional}，
 * 桥接代理会跳过），走 {@code EditGuard}（见 {@link EchoEditHook}）。
 * <p>
 * 通知写库放在 {@code handle} 返回之后：回退的写事务已经提交，不会与 SQLite 写锁相互等待；
 * 仍保留 afterCommit 兜底（万一将来 handle 被加上事务）。
 */
@Component
public final class EchoListeners implements CoreAopHook {

    private static final Logger log = LoggerFactory.getLogger(EchoListeners.class);

    private final EchoNotifier notifier;
    private final PluginDatabase database;
    private final WikiPageStore pageStore;
    private final EchoSupport support;
    private final CurrentUser currentUser;

    public EchoListeners(EchoNotifier notifier, PluginDatabase database,
                         WikiPageStore pageStore, EchoSupport support, CurrentUser currentUser) {
        this.notifier = notifier;
        this.database = database;
        this.pageStore = pageStore;
        this.support = support;
        this.currentUser = currentUser;
    }

    @Override
    public List<Advisor> advisors() {
        NameMatchMethodPointcut actionPointcut = new NameMatchMethodPointcut();
        actionPointcut.addMethodName("handle");
        MethodInterceptor actionAdvice = invocation -> {
            Object[] args = invocation.getArguments();
            boolean rollback = args.length > 0 && args[0] instanceof String action
                    && "rollback".equalsIgnoreCase(action);
            if (!rollback) {
                return invocation.proceed();
            }
            @SuppressWarnings("unchecked")
            Map<String, String> params = args.length > 1 && args[1] instanceof Map<?, ?> map
                    ? (Map<String, String>) map : Map.of();
            String title = params.get("title");
            List<Long> victims = title == null || title.isBlank()
                    ? List.of() : affectedUserIds(title, params);
            Object result = invocation.proceed();
            if (!victims.isEmpty() && !support.disableRevert()) {
                String summary = params.get("summary");
                Long actorUserId = rollbackActorUserId();
                afterCommit(() -> notifier.reverted(actorUserId, title, null, summary, victims));
            }
            return result;
        };
        return List.of(new DefaultPointcutAdvisor(actionPointcut, actionAdvice));
    }

    // ==================== 事务感知 ====================
    private void afterCommit(Runnable task) {        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            safe(task);
                        }
                    });
        } else {
            safe(task);
        }
    }

    private static void safe(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException exception) {
            log.warn("[echo] 通知写入失败（不影响动作）：{}", exception.toString());
        }
    }

    /** 回退操作者（当前请求用户；API 请求里 SessionUser 不可用时返回 null → 显示「系统」）。 */
    private Long rollbackActorUserId() {
        if (currentUser == null) {
            return null;
        }
        try {
            Viewer viewer = currentUser.viewer();
            return viewer == null || !viewer.loggedIn() ? null : viewer.id();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    // ==================== rollback 的被回退者 ====================

    /** 回退前查「目标用户对本页的连续编辑者」（与 ActionApiService.rollback 的判定一致）。 */
    private List<Long> affectedUserIds(String title, Map<String, String> params) {
        Long pageId = pageStore.byTitle(title)
                .map(PageSnapshot::id)
                .orElse(null);
        if (pageId == null) {
            return List.of();
        }
        List<Long> revisionUserIds = recentRevisionUserIds(pageId);
        if (revisionUserIds.size() < 2) {
            return List.of();
        }
        Long actorId = parseLong(params.get("userid"));
        if (actorId == null) {
            actorId = revisionUserIds.get(0);
        }
        if (actorId == null) {
            return List.of();
        }
        String targetName = params.get("user");
        if (targetName != null && !targetName.isBlank()) {
            Long targetId = userIdByName(targetName);
            if (targetId == null || !targetId.equals(actorId)) {
                return List.of(); // 与 rollback 的 notanactor 判定一致
            }
        }
        List<Long> victims = new ArrayList<>();
        for (Long userId : revisionUserIds) {
            if (userId == null || !userId.equals(actorId)) {
                break;
            }
            victims.add(userId);
        }
        return victims;
    }

    /** 该页最近的修订者（最新在前，回退发生前）。 */
    private List<Long> recentRevisionUserIds(long pageId) {
        List<Long> userIds = new ArrayList<>();
        for (DbRow row : database.query(
                "SELECT user_id FROM wiki_revision WHERE page_id = ? ORDER BY id DESC LIMIT 50", pageId)) {
            userIds.add(row.longOrNull("user_id"));
        }
        return userIds;
    }

    private Long userIdByName(String name) {
        return database.queryOne("SELECT id FROM wiki_user WHERE username = ?", name)
                .map(row -> row.longValue("id", 0L))
                .orElse(null);
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}