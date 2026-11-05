package cc.thonly.wikikernel.common.service;

import java.time.LocalDateTime;

/**
 * 插件时间显示桥：把按服务器时区保存的时间换算到当前请求的用户时区，再格式化。
 * 未设置用户时区时跟随站点 / JVM 时区；仅改变显示，不修改数据库中的时间。
 */
@FunctionalInterface
public interface TimeDisplay {
    /** {@code pattern} 为 Java 日期格式；空时间返回空串。 */
    String format(LocalDateTime time, String pattern);
}
