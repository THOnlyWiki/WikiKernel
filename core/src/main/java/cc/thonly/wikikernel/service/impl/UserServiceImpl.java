package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.log.LogTypeConfig;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.UserPreferences;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.time.WikiTime;
import cc.thonly.wikikernel.user.MediaWikiPassword;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import jakarta.annotation.Nullable;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class UserServiceImpl extends ServiceImpl<WikiUserMapper, WikiUser> implements UserService {

    @Resource
    WikiUserMapper wikiUserMapper;

    @Resource
    PermissionService permissionService;

    @Resource
    LogService logService;

    @Override
    public @Nullable WikiUser login(String username, String rawPassword) {
        if (Strings.isBlank(username) || rawPassword == null || rawPassword.isEmpty()) {
            return null;
        }
        WikiUser user = findByUsername(username).orElse(null);
        if (user == null) {
            return null;
        }
        // 校验兼容 MediaWiki 各代哈希（迁移过来的 user_password 直接可用）
        if (!MediaWikiPassword.verify(rawPassword, user.getPassword(), user.getId())) {
            return null;
        }
        // 旧格式哈希（无前缀 MD5 / A / B / 旧参数 pbkdf2 等）登录成功后升级为当前默认格式（与 MediaWiki 一致）
        if (MediaWikiPassword.needsRehash(user.getPassword())) {
            String upgraded = MediaWikiPassword.hash(rawPassword);
            // 带旧值条件更新：避免和并发改密互相覆盖
            wikiUserMapper.update(null, Wrappers.<WikiUser>lambdaUpdate()
                    .eq(WikiUser::getId, user.getId())
                    .eq(WikiUser::getPassword, user.getPassword())
                    .set(WikiUser::getPassword, upgraded));
            user.setPassword(upgraded);
        }
        return user;
    }

    @Override
    public WikiUser createUser(String username, String rawPassword, @Nullable String email) {
        return createUser(username, rawPassword, email, null);
    }

    @Override
    public WikiUser createUser(String username, String rawPassword, @Nullable String email,
                               @Nullable String timeZone) {
        String password = MediaWikiPassword.hash(rawPassword);
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword(password);
        user.setEmail(email == null ? "" : email);
        user.setCreateAt(LocalDateTime.now());
        // 注册时记下浏览器时区（非法 / 空则跟随服务器时区）
        user.setTimeZone(WikiTime.normalizeZone(timeZone));
        wikiUserMapper.insert(user);
        // 新账号自动进入“用户”组
        permissionService.grantDefault(user.getId());
        // 注册日志（操作者 = 本人）
        logService.record(LogTypeConfig.REGISTER, user.getId(), null, user.getId(), null);
        return user;
    }

    @Override
    public boolean isExisted(String username) {
        LambdaQueryWrapper<WikiUser> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(WikiUser::getUsername, username);
        return wikiUserMapper.exists(queryWrapper);
    }

    @Override
    public Optional<WikiUser> findByUsername(String username) {
        if (Strings.isBlank(username)) {
            return Optional.empty();
        }
        String value = username.trim();
        Optional<WikiUser> exact = Optional.ofNullable(wikiUserMapper.selectOne(Wrappers.<WikiUser>lambdaQuery()
                .eq(WikiUser::getUsername, value)
                .last("LIMIT 1")));
        if (exact.isPresent()) {
            return exact;
        }
        // 首字母自动大写后的用户页标题（用户:Abcd）也要能找到账号 abcd：
        // 查询忽略大小写（精确匹配优先），保证用户页 / 特殊页链接都能命中账号
        return Optional.ofNullable(wikiUserMapper.selectOne(Wrappers.<WikiUser>lambdaQuery()
                .apply("LOWER(username) = {0}", value.toLowerCase(Locale.ROOT))
                .last("LIMIT 1")));
    }

    @Override
    public Optional<WikiUser> rename(Long userId, String newUsername) {
        String value = Strings.trimToEmpty(newUsername);
        // 服务层防御：新名必须合法，且不能与旧名相同
        if (userId == null || !UserService.isValidUsername(value)) {
            return Optional.empty();
        }
        WikiUser user = wikiUserMapper.selectById(userId);
        if (user == null || value.equals(user.getUsername())) {
            return Optional.empty();
        }
        // wiki_user.username 没有唯一约束：重名前用 isExisted 查重
        if (isExisted(value)) {
            return Optional.empty();
        }
        user.setUsername(value);
        wikiUserMapper.updateById(user);
        return Optional.of(user);
    }

    @Override
    public Optional<WikiUser> findByEmail(String email) {
        String value = Strings.trimToEmpty(email);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(wikiUserMapper.selectOne(Wrappers.<WikiUser>lambdaQuery()
                .apply("LOWER(email) = {0}", value.toLowerCase(Locale.ROOT))
                .last("LIMIT 1")));
    }

    @Override
    public boolean updatePassword(long userId, String rawPassword) {
        WikiUser user = wikiUserMapper.selectById(userId);
        if (user == null) {
            return false;
        }
        user.setPassword(MediaWikiPassword.hash(rawPassword));
        wikiUserMapper.updateById(user);
        return true;
    }

    @Override
    public Map<Long, String> usernames(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return listByIds(ids).stream()
                .collect(Collectors.toMap(WikiUser::getId, WikiUser::getUsername, (first, second) -> first));
    }

    @Override
    public Map<Long, String> avatars(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return listByIds(ids).stream()
                .collect(Collectors.toMap(WikiUser::getId,
                        user -> Strings.trimToEmpty(user.getAvatar()), (first, second) -> first));
    }

    @Override
    public List<String> allUsernames() {
        return wikiUserMapper.selectList(Wrappers.<WikiUser>lambdaQuery()
                        .select(WikiUser::getUsername)
                        .orderByDesc(WikiUser::getId)
                        .last("LIMIT 500"))
                .stream()
                .map(WikiUser::getUsername)
                .toList();
    }

    @Override
    public Optional<WikiUser> findById(long userId) {
        return Optional.ofNullable(wikiUserMapper.selectById(userId));
    }

    @Override
    public WikiUser updatePreferences(long userId, UserPreferences preferences) {
        WikiUser user = wikiUserMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        user.setRealName(Strings.trimToEmpty(preferences.realName()));
        user.setGender(Strings.trimToEmpty(preferences.gender()));
        user.setLanguage(Strings.trimToEmpty(preferences.language()));
        user.setTimeZone(WikiTime.normalizeZone(preferences.timeZone()));
        user.setCountry(Strings.trimToEmpty(preferences.country()).toUpperCase(Locale.ROOT));
        user.setSignature(Strings.trimToEmpty(preferences.signature()));
        user.setSignatureWikitext(preferences.signatureWikitext());
        user.setEmail(Strings.trimToEmpty(preferences.email()));
        user.setEmailOnPasswordReset(preferences.emailOnPasswordReset());
        user.setEmailAllowOthers(preferences.emailAllowOthers());
        user.setEmailAllowNewUsers(preferences.emailAllowNewUsers());
        user.setEmailCopy(preferences.emailCopy());
        user.setRcLimit(preferences.rcLimit());
        user.setRcDays(preferences.rcDays());
        user.setSearchLimit(preferences.searchLimit());
        user.setGadgets(Strings.trimToEmpty(preferences.gadgets()));
        wikiUserMapper.updateById(user);
        return user;
    }

    @Override
    public WikiUser resetPreferences(long userId) {
        WikiUser user = wikiUserMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        // 注意：MyBatis-Plus updateById 忽略 null，可空文本列统一用空串表示“未设置”
        user.setSkin("");
        user.setRealName("");
        user.setGender("");
        user.setLanguage("");
        user.setTimeZone("");
        user.setCountry("");
        user.setSignature("");
        user.setSignatureWikitext(false);
        user.setEmailOnPasswordReset(true);
        user.setEmailAllowOthers(true);
        user.setEmailAllowNewUsers(true);
        user.setEmailCopy(false);
        user.setRcLimit(0);
        user.setRcDays(0);
        user.setSearchLimit(0);
        user.setGadgets("");
        wikiUserMapper.updateById(user);
        return user;
    }

    @Override
    public Optional<WikiUser> updateSkin(long userId, String skinId) {
        WikiUser user = wikiUserMapper.selectById(userId);
        if (user == null) {
            return Optional.empty();
        }
        user.setSkin(Strings.trimToEmpty(skinId));
        wikiUserMapper.updateById(user);
        return Optional.of(user);
    }

    @Override
    public Optional<WikiUser> updateAvatar(long userId, String avatarFile) {
        WikiUser user = wikiUserMapper.selectById(userId);
        if (user == null) {
            return Optional.empty();
        }
        user.setAvatar(Strings.trimToEmpty(avatarFile));
        wikiUserMapper.updateById(user);
        return Optional.of(user);
    }

    @Override
    public boolean markEmailConfirmed(long userId) {
        WikiUser user = wikiUserMapper.selectById(userId);
        if (user == null) {
            return false;
        }
        wikiUserMapper.update(null, Wrappers.<WikiUser>lambdaUpdate()
                .eq(WikiUser::getId, userId)
                .set(WikiUser::getEmailConfirmed, true)
                .set(WikiUser::getEmailConfirmedAt, LocalDateTime.now()));
        return true;
    }

    @Override
    public boolean clearEmailConfirmation(long userId) {
        WikiUser user = wikiUserMapper.selectById(userId);
        if (user == null) {
            return false;
        }
        wikiUserMapper.update(null, Wrappers.<WikiUser>lambdaUpdate()
                .eq(WikiUser::getId, userId)
                .set(WikiUser::getEmailConfirmed, false)
                .set(WikiUser::getEmailConfirmedAt, null));
        return true;
    }

    @Override
    public void markEmailConfirmSent(long userId) {
        wikiUserMapper.update(null, Wrappers.<WikiUser>lambdaUpdate()
                .eq(WikiUser::getId, userId)
                .set(WikiUser::getEmailConfirmSentAt, LocalDateTime.now()));
    }

    @Override
    public Optional<LocalDateTime> emailConfirmLastSentAt(long userId) {
        return Optional.ofNullable(wikiUserMapper.selectById(userId))
                .map(WikiUser::getEmailConfirmSentAt);
    }
}
