package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.EditSubmitter;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.EditHookService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.RequestEditContext;
import cc.thonly.wikikernel.web.Viewers;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 「像一次编辑那样提交」的桥实现：先跑平台保存前钩子（{@code EditGuard}），再保存页面。
 * <p>
 * 与网页 / API 提交的区别只在「没有表单」：钩子拿到的是 {@code source}（{@link #SOURCE_AI} 等）+
 * 正文 / 摘要 / 基准版本 + 客户端信息；{@code BLOCK} 拒绝、{@code HANDLED} 表示钩子接管
 * （例如审核把编辑写进队列）、{@code WARN} 保存后把说明回给调用方。
 * 版本冲突（乐观锁）与用户不存在也在这里统一处理，调用方不需要再查一次。
 */
@Service
public class EditSubmitterImpl implements EditSubmitter {

    private final EditHookService editHookService;
    private final WikiPageService wikiPageService;
    private final UserService userService;
    private final PermissionService permissionService;

    public EditSubmitterImpl(EditHookService editHookService, WikiPageService wikiPageService,
                             UserService userService, PermissionService permissionService) {
        this.editHookService = editHookService;
        this.wikiPageService = wikiPageService;
        this.userService = userService;
        this.permissionService = permissionService;
    }

    @Override
    public Result submit(Request request) {
        if (request == null) {
            return Result.failure("badRequest", "");
        }
        String title = Strings.trimToEmpty(request.title());
        if (title.isEmpty()) {
            return Result.failure("badTitle", "");
        }
        WikiUser actor = userService.findById(request.actorUserId()).orElse(null);
        if (actor == null) {
            return Result.failure("userMissing", "");
        }
        String content = request.content() == null ? "" : request.content();
        WikiPage existing = wikiPageService.getByTitle(title).orElse(null);
        boolean newPage = existing == null;

        RequestEditContext context = RequestEditContext.ai(null, Viewers.of(actor, permissionService),
                new RequestEditContext.FormValues(title, newPage, content,
                        Strings.trimToEmpty(request.summary()), request.minor(),
                        newPage ? "create" : "edit",
                        request.baseRevisionId(), request.parameters(), false));
        EditHookService.Decision decision = editHookService.beforeSave(context);
        if (decision.blocked()) {
            return Result.failure("blocked", decision.message());
        }
        if (decision.handled()) {
            return Result.handled(decision.message());
        }

        WikiPage saved;
        // 钩子登记的变更标签（如滥用过滤器的 tag 操作）随保存写入版本
        String changeTags = String.join(",", context.tags());
        if (request.baseRevisionId() != null) {
            saved = wikiPageService.editPageIfLatest(actor, title, content,
                    Strings.trimToEmpty(request.summary()), false, request.minor(), changeTags,
                    request.baseRevisionId());
            if (saved == null) {
                return Result.failure("conflict", "");
            }
        } else {
            saved = wikiPageService.editPage(actor, title, content,
                    Strings.trimToEmpty(request.summary()), false, request.minor(), changeTags);
        }
        if (saved == null) {
            return Result.failure("saveFailed", "");
        }
        Long revisionId = saved.getLatestRevisionId();
        if (request.aiAssisted() && revisionId != null) {
            wikiPageService.markAiAssisted(revisionId, request.aiModel());
        }
        List<String> warnings = decision.warnings() == null ? List.of() : decision.warnings();
        return Result.ok(saved.getId() == null ? 0 : saved.getId(),
                revisionId == null ? 0 : revisionId, warnings);
    }
}
