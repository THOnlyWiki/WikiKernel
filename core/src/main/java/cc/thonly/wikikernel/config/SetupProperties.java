package cc.thonly.wikikernel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 安装向导（{@code /init}）配置：
 *
 * <pre>
 * wikikernel:
 *   setup:
 *     mode: auto            # auto（没有 LocalSettings.yaml 时进入安装模式）| off | force
 *     file: LocalSettings.yaml
 *     restart-enabled: true # 完成安装后自动重启（测试 / IDE 环境可关）
 *     initial-accounts:     # 由安装向导写入；启动时自动创建账号并授予用户组
 *       - username: Admin
 *         password-hash: ":pbkdf2:sha512:30000:64:…"   # MediaWiki 格式哈希（旧字段 password-md5 仍兼容）
 *         email: ""
 *         groups: [ 管理员, 行政员 ]
 * </pre>
 */
@ConfigurationProperties(prefix = "wikikernel.setup")
public class SetupProperties {

    /** auto（配置文件不存在时进入安装模式）/ off（关闭）/ force（强制）。 */
    private String mode = "auto";

    /** 生成的本地配置文件（相对启动目录）。 */
    private String file = "LocalSettings.yaml";

    /** 完成安装后是否自动重启进程。 */
    private boolean restartEnabled = true;

    /** /init 安装密钥（首次完成安装时随机生成并写入 YAML；之后访问 /init 需要它）。 */
    private String key = "";

    /** 初始账号（由向导写入；启动时创建账号并授予用户组）。 */
    private List<InitialAccount> initialAccounts = new ArrayList<>();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public boolean isRestartEnabled() {
        return restartEnabled;
    }

    public void setRestartEnabled(boolean restartEnabled) {
        this.restartEnabled = restartEnabled;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public List<InitialAccount> getInitialAccounts() {
        return initialAccounts;
    }

    public void setInitialAccounts(List<InitialAccount> initialAccounts) {
        this.initialAccounts = initialAccounts == null ? new ArrayList<>() : initialAccounts;
    }

    /** 初始账号：密码存 MediaWiki 格式哈希（见 {@code password-hash}），不落明文。 */
    public static class InitialAccount {
        private String username = "";

        /** 密码哈希（MediaWiki 格式，如 {@code :pbkdf2:…}）。 */
        private String passwordHash = "";

        /** 旧字段 {@code password-md5}：只支持 MD5 哈希值（历史配置兼容，登录成功后会自动升级）。 */
        private String passwordMd5 = "";

        private String email = "";
        private List<String> groups = new ArrayList<>();

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        public String getPasswordMd5() {
            return passwordMd5;
        }

        public void setPasswordMd5(String passwordMd5) {
            this.passwordMd5 = passwordMd5;
        }

        /** 实际使用的密码哈希：优先 {@code password-hash}，为空时回退到旧的 {@code password-md5}。 */
        public String effectivePasswordHash() {
            if (passwordHash != null && !passwordHash.isBlank()) {
                return passwordHash;
            }
            return passwordMd5 == null ? "" : passwordMd5;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public List<String> getGroups() {
            return groups;
        }

        public void setGroups(List<String> groups) {
            this.groups = groups == null ? new ArrayList<>() : groups;
        }
    }
}
