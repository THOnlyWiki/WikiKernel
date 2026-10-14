package cc.thonly.wikikernel.common.service;

/**
 * 页面写入桥（由 core 提供实现，插件可 {@code @Autowired} 注入）：**绕过网页表单**直接保存 / 删除页面。
 * <p>
 * 用途：审核（Moderation）通过待审更改、AI 代理确认提交、导入 / 维护脚本等
 * 「以某个用户的身份写页面」的场景。表单里才有的东西（验证码、审核排队）不在这里；
 * 调用方负责权限与业务校验（AI 代理 / 审核插件自己复查权限、过滤器与版本）。
 */
public interface PageWriter {

    /**
     * 一次保存请求。
     *
     * @param actorUserId        以谁的身份保存（必须存在）
     * @param title              页面标题（含命名空间）
     * @param content            新正文
     * @param summary            编辑摘要
     * @param minor              小编辑
     * @param bot                机器人编辑（不在最近更改里显示）
     * @param tags               变更标签（逗号分隔，可空）
     * @param expectedRevisionId 期望的当前版本（乐观锁）：不为 null 时若页面已被别人改过则**不保存**，
     *                           返回 {@code conflict}；为 null 时不做版本检查
     * @param aiAssisted         是否 AI 协助的版本（打隐藏水印）
     * @param aiModel            AI 厂商 / 模型（{@code aiAssisted=true} 时记录）
     */
    record Request(long actorUserId, String title, String content, String summary,
                   boolean minor, boolean bot, String tags, Long expectedRevisionId,
                   boolean aiAssisted, String aiModel) {

        /** 常用简写：不做版本检查、非 AI、非小编辑。 */
        public static Request of(long actorUserId, String title, String content, String summary) {
            return new Request(actorUserId, title, content, summary, false, false, null, null, false, null);
        }

        /** 常用简写：带乐观锁与 AI 标记（AI 代理用）。 */
        public static Request aiAssisted(long actorUserId, String title, String content, String summary,
                                         Long expectedRevisionId, String aiModel) {
            return new Request(actorUserId, title, content, summary, false, false, null,
                    expectedRevisionId, true, aiModel);
        }
    }

    /**
     * 保存结果。
     *
     * @param saved      是否写入
     * @param error      失败原因（{@code conflict} = 版本冲突；{@code userMissing} = 用户不存在；其它为消息键）
     * @param pageId     页面 ID（保存成功时）
     * @param revisionId 新版本 ID（保存成功时）
     */
    record Result(boolean saved, String error, Long pageId, Long revisionId) {

        public static Result ok(long pageId, long revisionId) {
            return new Result(true, null, pageId, revisionId);
        }

        public static Result failure(String error) {
            return new Result(false, error, null, null);
        }
    }

    /** 保存一个版本（标题不存在时自动创建）。 */
    Result save(Request request);

    /** 删除页面（软删除，历史保留）；页面不存在返回 {@code failure("pageMissing")}。 */
    Result delete(long actorUserId, String title, String summary);
}
