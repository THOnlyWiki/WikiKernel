package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.data.WikiUserPreference;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserPreferenceMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 用户偏好桥：把 {@code wiki_user} 的已知偏好列与通用键值表
 * （{@code wiki_user_preference}）统一暴露给插件（{@code common.service.UserSettings}）。
 * <p>
 * 已知列（如 Gadgets 的 {@code gadgets}）走实体字段，保证与 core 自己的读写一致；
 * 其它键落在键值表里，插件不需要自己建表 / 写 SQL。
 */
@Service
public class UserSettingsImpl implements UserSettings {

    /** 落在 {@code wiki_user} 列上的偏好键（键名 → 读写器）。 */
    private static final Set<String> COLUMN_KEYS = Set.of(
            "gadgets", "skin", "language", "timeZone", "country", "gender", "realName", "signature", "avatar",
            "rcLimit", "rcDays", "searchLimit");

    private final WikiUserMapper userMapper;
    private final WikiUserPreferenceMapper preferenceMapper;

    public UserSettingsImpl(WikiUserMapper userMapper, WikiUserPreferenceMapper preferenceMapper) {
        this.userMapper = userMapper;
        this.preferenceMapper = preferenceMapper;
    }

    @Override
    public String get(long userId, String key, String defaultValue) {
        if (key == null || key.isBlank()) {
            return defaultValue;
        }
        if (COLUMN_KEYS.contains(key)) {
            WikiUser user = userMapper.selectById(userId);
            if (user == null) {
                return defaultValue;
            }
            String value = switch (key) {
                case "gadgets" -> user.getGadgets();
                case "skin" -> user.getSkin();
                case "language" -> user.getLanguage();
                case "timeZone" -> user.getTimeZone();
                case "country" -> user.getCountry();
                case "gender" -> user.getGender();
                case "realName" -> user.getRealName();
                case "signature" -> user.getSignature();
                case "avatar" -> user.getAvatar();
                case "rcLimit" -> user.getRcLimit() == null ? null : user.getRcLimit().toString();
                case "rcDays" -> user.getRcDays() == null ? null : user.getRcDays().toString();
                case "searchLimit" -> user.getSearchLimit() == null ? null : user.getSearchLimit().toString();
                default -> null;
            };
            return value == null ? defaultValue : value;
        }
        WikiUserPreference row = preferenceMapper.selectOne(Wrappers.<WikiUserPreference>lambdaQuery()
                .eq(WikiUserPreference::getUserId, userId)
                .eq(WikiUserPreference::getPrefKey, key)
                .last("limit 1"));
        return row == null || row.getPrefValue() == null ? defaultValue : row.getPrefValue();
    }

    @Override
    public void set(long userId, String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (COLUMN_KEYS.contains(key)) {
            WikiUser user = userMapper.selectById(userId);
            if (user == null) {
                return;
            }
            String text = value == null ? "" : value;
            switch (key) {
                case "gadgets" -> user.setGadgets(text);
                case "skin" -> user.setSkin(text.isEmpty() ? null : text);
                case "language" -> user.setLanguage(text.isEmpty() ? null : text);
                case "timeZone" -> user.setTimeZone(text.isEmpty() ? null : text);
                case "country" -> user.setCountry(text.toUpperCase(Locale.ROOT));
                case "gender" -> user.setGender(text);
                case "realName" -> user.setRealName(text);
                case "signature" -> user.setSignature(text);
                case "avatar" -> user.setAvatar(text);
                case "rcLimit", "rcDays", "searchLimit" -> {
                    Integer number = text.isBlank() ? 0 : parse(text);
                    if ("rcLimit".equals(key)) {
                        user.setRcLimit(number);
                    } else if ("rcDays".equals(key)) {
                        user.setRcDays(number);
                    } else {
                        user.setSearchLimit(number);
                    }
                }
                default -> {
                    return;
                }
            }
            userMapper.updateById(user);
            return;
        }
        WikiUserPreference existing = preferenceMapper.selectOne(Wrappers.<WikiUserPreference>lambdaQuery()
                .eq(WikiUserPreference::getUserId, userId)
                .eq(WikiUserPreference::getPrefKey, key)
                .last("limit 1"));
        if (existing == null) {
            if (value == null || value.isEmpty()) {
                return;
            }
            preferenceMapper.insert(new WikiUserPreference(null, userId, key, value));
            return;
        }
        existing.setPrefValue(value == null ? "" : value);
        preferenceMapper.updateById(existing);
    }

    @Override
    public Map<String, String> list(long userId, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        List<WikiUserPreference> rows = preferenceMapper.selectList(Wrappers.<WikiUserPreference>lambdaQuery()
                .eq(WikiUserPreference::getUserId, userId));
        for (WikiUserPreference row : rows) {
            if (prefix == null || prefix.isEmpty() || row.getPrefKey().startsWith(prefix)) {
                result.put(row.getPrefKey(), row.getPrefValue() == null ? "" : row.getPrefValue());
            }
        }
        return result;
    }

    private static Integer parse(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
