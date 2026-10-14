package cc.thonly.wikikernel.common.access;

import java.util.Optional;

/**
 * 可配置权限的动作（对应 {@code wiki_access_rule.action} 与网页「特殊:权限配置」）。
 * <p>
 * 每个动作都可以按目标（页面 / 特殊页 / 前缀 / 全部）配置允许的用户组；
 * 具体解析与兜底默认值见 core 的 {@code AccessControlService}。
 */
public enum AccessAction {

    READ("read"),
    EDIT("edit"),
    CREATE("create"),
    MOVE("move"),
    PROTECT("protect"),
    UPLOAD("upload"),
    DELETE("delete"),
    /** 永久删除文件（彻底删除，含全部历史版本；默认管理员）。 */
    PURGE("purge"),
    BLOCK("block");

    /** groups 特殊值：所有人（包含匿名用户）。 */
    public static final String EVERYONE = "*";

    /** 多个用户组之间的分隔符（存储格式，如 {@code 管理员|行政员}）。 */
    public static final String GROUP_SEPARATOR = "|";

    private final String id;

    AccessAction(String id) {
        this.id = id;
    }

    /** 稳定 id（存储、URL 参数与 LocalSettings 都用它，不随语言变化）。 */
    public String id() {
        return id;
    }

    public static Optional<AccessAction> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase();
        for (AccessAction action : values()) {
            if (action.id.equals(normalized)) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }
}
