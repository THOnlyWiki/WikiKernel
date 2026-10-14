package cc.thonly.wikikernel.common.service;

/**
 * 操作日志写入桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 日志类型用 {@code common.extension.LogType} 注册（插件可注册自己的类型），
 * 展示文案由 core 的 {@code LogFormatter} 按当前语言生成；写进 {@code wiki_log}，
 * 可在 {@code 特殊:日志} 过滤查看。
 */
public interface LogWriter {

    /**
     * 记一条日志。
     *
     * @param logType      类型 id（{@code LogType} 注册中心里的 id）
     * @param actorUserId  操作者用户 ID（匿名 / 系统为 null）
     * @param target       文本目标（页面标题等，可空）
     * @param targetUserId 用户目标（权限变更等，可空）
     * @param detail       详情（编辑摘要、{@code grant|管理员} 等，可空）
     */
    void record(String logType, Long actorUserId, String target, Long targetUserId, String detail);

    /** 便捷：没有用户目标的日志。 */
    default void record(String logType, Long actorUserId, String target, String detail) {
        record(logType, actorUserId, target, null, detail);
    }
}
