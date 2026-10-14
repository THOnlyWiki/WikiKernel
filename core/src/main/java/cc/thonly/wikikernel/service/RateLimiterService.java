package cc.thonly.wikikernel.service;

/**
 * 请求速率限制（防字典爆破）：滑动窗口统计失败次数，达到阈值后锁定一段时间。
 * <p>
 * 调用约定：请求进入受保护逻辑前先 {@link #check 查询}（被锁则直接拒绝），
 * 失败时 {@link #failure 记一次失败}，成功时 {@link #success 清除记录}。
 */
public interface RateLimiterService {

    /** 分类：登录接口（键 = IP + 用户名）。 */
    String LOGIN = "login";
    /** 分类：/init 安装密钥（键 = IP）。 */
    String SETUP = "setup";
    /** 分类：密码重置请求（键 = IP）。 */
    String PASSWORD_RESET = "password-reset";

    /** AI 代理对话（按用户限流；规则见 {@code wikikernel.rate-limit.ai-agent}）。 */
    String AI_AGENT = "ai-agent";

    /** 浏览器指纹登录（插件 fingerprint-login；键 = 客户端 IP + 指纹摘要，规则见 {@code wikikernel.rate-limit.fingerprint-login}）。 */
    String FINGERPRINT_LOGIN = "fingerprint-login";

    /** 浏览器指纹登录：只按客户端 IP 的一层（换指纹绕过时兜底；规则见 {@code wikikernel.rate-limit.fingerprint-login-ip}）。 */
    String FINGERPRINT_LOGIN_IP = "fingerprint-login-ip";

    /**
     * 限流判定结果。
     *
     * @param limited           是否被锁定
     * @param retryAfterSeconds 被锁时建议的重试等待秒数（未锁为 0）
     */
    public record Decision(boolean limited, long retryAfterSeconds) {

        public static Decision allowed() {
            return new Decision(false, 0);
        }

        public static Decision blocked(long retryAfterSeconds) {
            return new Decision(true, retryAfterSeconds);
        }
    }

    /** 是否已被锁定（只读；被锁时给出建议重试秒数）。 */
    Decision check(String category, String key);

    /** 记一次失败（窗口内失败达到上限后进入锁定）。 */
    void failure(String category, String key);

    /** 记一次事件（同 {@link #failure}；用于「按请求计数」的分类，如密码重置）。 */
    default void hit(String category, String key) {
        failure(category, key);
    }

    /** 成功：清除该键的失败记录与锁定。 */
    void success(String category, String key);
}
