package cc.thonly.wikikernel.plugin.abusefilter.service;

import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilter;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilterRevision;
import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseLog;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterSupport;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 滥用过滤器（仿 MediaWiki 的 AbuseFilter 扩展，迁移前在 core 的 {@code AbuseFilterService}）：
 * 过滤器 = 说明 + 条件表达式 + 操作集合；命中后按操作阻止 / 警告 / 打标签 / 节流 / 封禁，
 * 并把变量快照写入滥用日志（自己的表 {@code wiki_abuse_log}）。
 * <p>
 * 功能开关沿用 core 的站点设置键 {@code features.abuse-filter}（默认关闭）；
 * 数据访问全部走 {@code common.service.AbuseFilterStore} 等平台桥，插件不碰 core 的 Mapper。
 */
public interface AbuseFilterService {

    /** 过滤器操作名（与 MediaWiki 一致）。 */
    List<String> ACTION_NAMES = List.of(
            "disallow", "warn", "tag", "throttle", "block", "blockautopromote", "degrade");

    /** 系统账号名（用于过滤器封禁等操作；插件启用时自动创建，属于「机器人」组）。 */
    String SYSTEM_ACCOUNT = "滥用过滤器";

    /** 系统账号所属用户组（core 的 {@code PermissionService.GROUP_BOT}）。 */
    String SYSTEM_ACCOUNT_GROUP = AbuseFilterSupport.GROUP_BOT;

    /** 功能开关是否启用（关闭时不评估任何过滤器、不写滥用日志）。 */
    boolean enabled();

    /** 系统账号不存在时创建（随机密码 + 「机器人」组）；返回是否新建。 */
    boolean ensureSystemAccount();

    /** 过滤器列表（新的在前）；{@code includeHidden=false} 时不返回隐藏过滤器。 */
    List<WikiAbuseFilter> filters(boolean includeHidden);

    /** 按编号查过滤器。 */
    Optional<WikiAbuseFilter> find(long id);

    /**
     * 保存 / 新建过滤器（id = 0 表示新建）；条件语法错误抛
     * {@link AbuseCondition.SyntaxException}。
     *
     * @param actorId 修改者 ID（{@code null} = 系统）
     */
    long save(long id, FilterForm form, Long actorId);

    /** 修改历史（新的在前）。 */
    List<WikiAbuseFilterRevision> history(long filterId);

    /** 恢复到某个历史版本（写一条新修订）；历史不存在返回 false。 */
    boolean restore(long filterId, long revisionId, Long actorId);

    /**
     * 求值：跑所有启用的过滤器。
     *
     * @param action    动作名（{@code edit} / {@code move} / {@code createaccount} / {@code upload} / {@code delete} / {@code block}…）
     * @param viewer    触发者（匿名用 {@link Viewer#ANONYMOUS}）
     * @param ip        触发者 IP（可空串）
     * @param variables 动作相关变量（见 {@link AbuseVariables}），会与 {@code user_*} 等基础变量合并
     */
    Outcome evaluate(String action, Viewer viewer, String ip, Map<String, Object> variables);

    /** 测试页：用表达式匹配最近编辑，返回逐条结果。 */
    List<TestHit> test(String pattern, int limit);

    /** 滥用日志（新的在前）；{@code includeHided=false} 时不返回隐藏条目。 */
    List<WikiAbuseLog> logs(boolean includeHided, int limit);

    /** 按编号查日志条目。 */
    Optional<WikiAbuseLog> log(long id);

    /** 隐藏 / 显示一条日志条目。 */
    boolean hideLog(long id, boolean hided);

    /** 日志里的变量快照（JSON → Map；损坏时返回空 Map）。 */
    Map<String, Object> logVariables(WikiAbuseLog log);

    /** 过滤器表单字段。 */
    record FilterForm(String description, String pattern, String actions, String tags,
                      boolean enabled, boolean deleted, boolean hidden,
                      int throttleCount, int throttlePeriod, String throttleGroup,
                      String comment, String notes,
                      String warnMessage, String warnPage,
                      String disallowMessage, String disallowPage,
                      boolean blockTalk, int blockAnonDays, int blockUserDays) {

        /** 兼容旧调用：没有新增字段时使用默认值（3 天封禁、无自定义文案）。 */
        public FilterForm(String description, String pattern, String actions, String tags,
                          boolean enabled, boolean deleted, boolean hidden,
                          int throttleCount, int throttlePeriod, String throttleGroup,
                          String comment) {
            this(description, pattern, actions, tags, enabled, deleted, hidden,
                    throttleCount, throttlePeriod, throttleGroup, comment,
                    "", "", "", "", "", false, 3, 3);
        }
    }

    /**
     * 一次求值的结果。
     *
     * @param disallowed 是否被禁止（调用方应拒绝本次操作并展示 messages）
     * @param messages   禁止原因（过滤器说明）
     * @param warnings   警告（调用方可要求再次确认）
     * @param tags       要添加的变更标签（逗号分隔，可空）
     * @param filterIds  命中的过滤器编号
     */
    record Outcome(boolean disallowed, List<String> messages, List<String> warnings,
                   String tags, List<Long> filterIds) {

        public Outcome {
            messages = messages == null ? List.of() : List.copyOf(messages);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            tags = tags == null ? "" : tags;
            filterIds = filterIds == null ? List.of() : List.copyOf(filterIds);
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }

        /** 标签列表（{@code tags} 逗号分隔 → 列表）。 */
        public List<String> tagList() {
            if (tags.isBlank()) {
                return List.of();
            }
            return List.of(tags.split(",")).stream().map(String::trim).filter(tag -> !tag.isEmpty()).toList();
        }

        public static Outcome none() {
            return new Outcome(false, List.of(), List.of(), "", List.of());
        }
    }

    /** 测试页的一行结果。 */
    record TestHit(long revisionId, String pageTitle, String userName, LocalDateTime time,
                   String summary, boolean matched) {
    }
}
