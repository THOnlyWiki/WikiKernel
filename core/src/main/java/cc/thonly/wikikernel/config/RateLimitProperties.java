package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 请求速率限制（防字典爆破；LocalSettings.yaml 的 {@code wikikernel.security.rate-limit.*}）。
 * <p>
 * 按「分类 + 键」统计失败次数：窗口（{@code window-seconds}）内失败达到 {@code max-attempts} 次后
 * 锁定 {@code lock-seconds} 秒（返回 429），成功一次立即清除该键的失败记录。
 * 键的取法：登录 = 客户端 IP + 用户名，/init 密钥 = 客户端 IP，密码重置 = 客户端 IP。
 * <p>
 * 多实例部署时各实例独立计数（单机防爆破足够）。
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.security.rate-limit")
public class RateLimitProperties {

    /** 总开关（false = 不限制）。 */
    private boolean enabled = true;

    /** 登录接口（{@code POST /api/user/login}）：按 IP + 用户名 记失败。 */
    private Rule login = new Rule(5, 300, 300);

    /** {@code /init} 安装密钥（GET 的 {@code ?key=} 与 {@code POST /api/setup/unlock}）：按 IP 记失败。 */
    private Rule setup = new Rule(5, 300, 300);

    /** 密码重置请求：按 IP 记失败（防止被用来刷别人的邮箱）。 */
    private Rule passwordReset = new Rule(10, 3600, 900);

    /** AI 代理对话：默认每用户每小时 30 次（一次对话可能触发多轮模型调用，但只计一次）。 */
    private Rule aiAgent = new Rule(30, 3600, 600);

    /**
     * 浏览器指纹登录（插件 fingerprint-login）：按「客户端 IP + 指纹摘要」记失败，
     * 防止拿一批候选指纹刷登录。默认 5 次失败 / 5 分钟 → 锁 5 分钟。
     */
    private Rule fingerprintLogin = new Rule(5, 300, 300);

    /** 浏览器指纹登录：只按客户端 IP 的一层（换指纹绕过时兜底）。默认 10 次失败 / 5 分钟。 */
    private Rule fingerprintLoginIp = new Rule(10, 300, 300);

    /** 一条限制规则。 */
    @Data
    public static class Rule {

        /** 窗口内允许的失败次数（达到后锁定）。 */
        private int maxAttempts;

        /** 统计窗口（秒）。 */
        private int windowSeconds;

        /** 触发后的锁定时长（秒）。 */
        private int lockSeconds;

        public Rule() {
        }

        public Rule(int maxAttempts, int windowSeconds, int lockSeconds) {
            this.maxAttempts = maxAttempts;
            this.windowSeconds = windowSeconds;
            this.lockSeconds = lockSeconds;
        }
    }
}
