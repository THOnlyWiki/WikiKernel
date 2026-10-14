package cc.thonly.wikikernel.common.service;

import java.util.Map;

/**
 * 用户偏好读写桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 已知的偏好字段对应 {@code wiki_user} 的列（如 {@code gadgets} / {@code skin} / {@code language} /
 * {@code timeZone} / {@code country} / {@code rcLimit}…）；其它键存放在通用的用户偏好表（{@code wiki_user_preference}，
 * 键值对，插件自己的偏好用它）。
 */
public interface UserSettings {

    /** 读取偏好：没有值时返回 defaultValue（数值字段按十进制字符串返回）。 */
    String get(long userId, String key, String defaultValue);

    /** 写入偏好（空值 = 清空该键）。 */
    void set(long userId, String key, String value);

    /** 读取某个前缀下的全部偏好（插件偏好表；键为完整键名）。 */
    Map<String, String> list(long userId, String prefix);
}
