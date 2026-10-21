package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限相关配置（LocalSettings.yaml 的 {@code wikikernel.permissions}）。
 */
@Data
@ConfigurationProperties(prefix = "wikikernel.permissions")
public class PermissionProperties {

    /**
     * 首次搭建用：这些用户名在启动时自动获得“管理员 + 行政员”（可进用户页/权限页分配权限）。
     * <p>
     * 只提升、不降权；留空表示不做任何事。
     */
    private List<String> bootstrapAdmins = new ArrayList<>();

    /**
     * 访问控制默认规则（仿 MediaWiki LocalSettings.php 的 $wgGroupPermissions）：
     * 每次启动都会写入数据库（覆盖同「目标 + 动作」的网页修改）；网页上新增的规则不受影响。
     * <p>
     * 示例：
     * <pre>
     * wikikernel:
     *   permissions:
     *     rules:
     *       - target: "*"          # 全部页面
     *         action: read         # read/edit/create/move/protect/delete
     *         groups: "*"          # * = 所有人（含匿名）；空 = 无人；多个用 | 分隔
     *       - target: "帮助:*"     # 以 * 结尾表示前缀
     *         action: create
     *         groups: "自动确定用户"
     *       - target: "特殊:用户权限"
     *         action: read
     *         groups: "管理员|行政员"
     * </pre>
     */
    private List<AccessRuleConfig> rules = new ArrayList<>();

    /**
     * 是否允许编辑其他用户的用户页（{@code 用户:XXX}）；null = 不改数据库里的设置。
     * 管理员始终可以编辑；用户本人编辑自己的用户页不受此限制。
     */
    private Boolean allowOtherUserPageEdit;

    /**
     * 是否允许未登录用户查看「特殊:最近更改」；null = 不改数据库里的设置。
     * 关闭后匿名访问只显示登录提示，已登录用户不受影响。
     */
    private Boolean allowAnonymousRecentChanges;

    /** 一条访问控制规则的配置形态（LocalSettings.yaml）。 */
    @Data
    public static class AccessRuleConfig {

        /** 目标：* / 精确标题 / 前缀（以 * 结尾，如 帮助:*）/ 特殊页（如 特殊:日志）。 */
        private String target;

        /** 动作：read / edit / create / move / protect / delete。 */
        private String action;

        /** 允许的用户组（| 分隔）；* = 所有人（含匿名）；空 = 无人。 */
        private String groups;
    }
}
