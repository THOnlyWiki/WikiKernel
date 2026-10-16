package cc.thonly.wikikernel.log;

import cc.thonly.wikikernel.common.extension.LogType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内置日志类型（注册进 LogTypeRegistry）：
 * 创建页面 / 编辑页面 / 删除页面 / 用户注册 / 权限变更。
 * 插件可通过 {@code PluginContext.register(LogType.class, ...)} 追加自己的类型。
 */
@Configuration(proxyBeanMethods = false)
public class LogTypeConfig {

    public static final String CREATE = "create";
    public static final String EDIT = "edit";
    public static final String DELETE = "delete";
    public static final String MOVE = "move";
    public static final String PROTECT = "protect";
    public static final String REGISTER = "register";
    public static final String RIGHTS = "rights";
    public static final String UPLOAD = "upload";
    public static final String FILEDELETE = "filedelete";
    public static final String FILERESTORE = "filerestore";
    /** 页面恢复（{@code 特殊:已删除页面} 里把已删除页面恢复出来）。 */
    public static final String RESTORE = "restore";
    /**
     * 用户合并（{@code usermerge} 日志类型由插件 {@code user-merge} 注册；此常量仅供 core 内引用
     * 字符串 id，不再注册 {@code LogType} Bean）。
     */
    public static final String USERMERGE = "usermerge";
    /**
     * 重命名用户（{@code renameuser} 日志类型由插件 {@code rename-user} 注册；此常量仅供 core 内引用
     * 字符串 id，不再注册 {@code LogType} Bean）。
     */
    public static final String RENAMEUSER = "renameuser";
    public static final String BLOCK = "block";
    public static final String UNBLOCK = "unblock";
    public static final String COMMENT = "comment";
    public static final String COMMENTDELETE = "commentdelete";
    public static final String IMPORT = "import";

    @Bean
    public LogType createLogType() {
        return new BuiltInLogType(CREATE, LogType.TargetKind.PAGE, 10);
    }

    @Bean
    public LogType editLogType() {
        return new BuiltInLogType(EDIT, LogType.TargetKind.PAGE, 20);
    }

    @Bean
    public LogType deleteLogType() {
        return new BuiltInLogType(DELETE, LogType.TargetKind.PAGE, 30);
    }

    @Bean
    public LogType moveLogType() {
        return new BuiltInLogType(MOVE, LogType.TargetKind.PAGE, 35);
    }

    @Bean
    public LogType protectLogType() {
        return new BuiltInLogType(PROTECT, LogType.TargetKind.PAGE, 40);
    }

    @Bean
    public LogType registerLogType() {
        return new BuiltInLogType(REGISTER, LogType.TargetKind.USER, 40);
    }

    @Bean
    public LogType rightsLogType() {
        return new BuiltInLogType(RIGHTS, LogType.TargetKind.USER, 50);
    }

    @Bean
    public LogType uploadLogType() {
        return new BuiltInLogType(UPLOAD, LogType.TargetKind.FILE, 60);
    }

    @Bean
    public LogType fileDeleteLogType() {
        return new BuiltInLogType(FILEDELETE, LogType.TargetKind.FILE, 65);
    }

    @Bean
    public LogType fileRestoreLogType() {
        return new BuiltInLogType(FILERESTORE, LogType.TargetKind.FILE, 67);
    }

    @Bean
    public LogType blockLogType() {
        return new BuiltInLogType(BLOCK, LogType.TargetKind.TEXT, 45);
    }

    @Bean
    public LogType unblockLogType() {
        return new BuiltInLogType(UNBLOCK, LogType.TargetKind.TEXT, 46);
    }

    @Bean
    public LogType commentLogType() {
        return new BuiltInLogType(COMMENT, LogType.TargetKind.PAGE, 70);
    }

    @Bean
    public LogType commentDeleteLogType() {
        return new BuiltInLogType(COMMENTDELETE, LogType.TargetKind.PAGE, 71);
    }

    @Bean
    public LogType importLogType() {
        return new BuiltInLogType(IMPORT, LogType.TargetKind.PAGE, 80);
    }
}
