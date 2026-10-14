package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.PageWriter;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WikiPageService;
import org.springframework.stereotype.Service;

/**
 * 页面写入桥：把 core 的页面保存能力暴露给插件（审核通过、AI 代理提交等）。
 * <p>
 * 不经过网页表单：验证码、审核排队等「表单流程」的东西不在这里（调用方自己处理）。
 * 需要并发保护时传 {@code expectedRevisionId}（乐观锁，冲突不落盘）。
 */
@Service
public class PageWriterImpl implements PageWriter {

    private final WikiPageService wikiPageService;
    private final UserService userService;

    public PageWriterImpl(WikiPageService wikiPageService, UserService userService) {
        this.wikiPageService = wikiPageService;
        this.userService = userService;
    }

    @Override
    public Result save(Request request) {
        if (request == null) {
            return Result.failure("badRequest");
        }
        String title = Strings.trimToEmpty(request.title());
        if (title.isEmpty()) {
            return Result.failure("badTitle");
        }
        WikiUser actor = userService.findById(request.actorUserId()).orElse(null);
        if (actor == null) {
            return Result.failure("userMissing");
        }
        String summary = request.summary() == null ? "" : request.summary();
        String tags = request.tags();
        WikiPage page;
        if (request.expectedRevisionId() != null) {
            page = wikiPageService.editPageIfLatest(actor, title,
                    request.content() == null ? "" : request.content(), summary,
                    request.bot(), request.minor(), tags, request.expectedRevisionId());
            if (page == null) {
                return Result.failure("conflict");
            }
        } else {
            page = wikiPageService.editPage(actor, title,
                    request.content() == null ? "" : request.content(), summary,
                    request.bot(), request.minor(), tags);
        }
        if (page == null) {
            return Result.failure("saveFailed");
        }
        Long revisionId = page.getLatestRevisionId();
        if (request.aiAssisted() && revisionId != null) {
            wikiPageService.markAiAssisted(revisionId, request.aiModel());
        }
        return Result.ok(page.getId() == null ? 0 : page.getId(), revisionId == null ? 0 : revisionId);
    }

    @Override
    public Result delete(long actorUserId, String title, String summary) {
        WikiUser actor = userService.findById(actorUserId).orElse(null);
        if (actor == null) {
            return Result.failure("userMissing");
        }
        WikiPage page = wikiPageService.deletePage(actor, Strings.trimToEmpty(title));
        return page == null ? Result.failure("pageMissing") : Result.ok(page.getId(), 0);
    }
}
