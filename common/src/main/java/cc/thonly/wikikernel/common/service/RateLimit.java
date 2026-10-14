package cc.thonly.wikikernel.common.service;

/**
 * 请求限流桥（由 core 提供实现，插件可 {@code @Autowired} 注入）：
 * 与 core 的登录 / 安装向导 / 密码重置共用同一套「滑动窗口 + 锁定」实现，
 * 规则（上限 / 窗口 / 锁定时长）仍由站点配置 {@code wikikernel.rate-limit.<分类>} 决定。
 * <p>
 * 调用约定：进入受保护逻辑前先 {@link #check}（被锁则直接拒绝），再 {@link #hit} 记一次事件。
 */
public interface RateLimit {

    /**
     * 限流判定结果。
     *
     * @param limited           是否被锁定
     * @param retryAfterSeconds 被锁时建议的重试等待秒数（未锁为 0）
     */
    record Decision(boolean limited, long retryAfterSeconds) {

        public static Decision allowed() {
            return new Decision(false, 0);
        }

        public static Decision blocked(long retryAfterSeconds) {
            return new Decision(true, retryAfterSeconds);
        }
    }

    /** 是否已被锁定（只读；被锁时给出建议重试秒数）。 */
    Decision check(String category, String key);

    /** 记一次事件（窗口内的次数达到上限后进入锁定）。 */
    void hit(String category, String key);

    /** 清除该键的记录与锁定（成功路径用）。 */
    void success(String category, String key);
}
