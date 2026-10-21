package cc.thonly.wikikernel.auth.external;

import cc.thonly.wikikernel.common.data.WikiExternalIdentity;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthProvider;
import cc.thonly.wikikernel.common.externalauth.ExternalAuthUser;
import cc.thonly.wikikernel.db.mapper.WikiExternalIdentityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 第三方身份绑定表读写。 */
@Service
public class ExternalIdentityService {

    private static final HexFormat HEX = HexFormat.of();

    private final WikiExternalIdentityMapper mapper;

    public ExternalIdentityService(WikiExternalIdentityMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<WikiExternalIdentity> find(String provider, String subject) {
        String key = bindingKey(provider, subject);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<WikiExternalIdentity>lambdaQuery()
                .eq(WikiExternalIdentity::getBindingKey, key)
                .last("LIMIT 1")));
    }

    public Optional<WikiExternalIdentity> findForUser(long userId, String provider) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<WikiExternalIdentity>lambdaQuery()
                .eq(WikiExternalIdentity::getUserId, userId)
                .eq(WikiExternalIdentity::getProvider, normalizeProvider(provider))
                .last("LIMIT 1")));
    }

    public List<WikiExternalIdentity> forUser(long userId) {
        return mapper.selectList(Wrappers.<WikiExternalIdentity>lambdaQuery()
                .eq(WikiExternalIdentity::getUserId, userId)
                .orderByAsc(WikiExternalIdentity::getId));
    }

    /**
     * 绑定身份。已有同一 provider + subject 且属于其他用户时返回空（由调用方提示冲突）；
     * 属于同一用户时更新 provider 侧资料。
     */
    public synchronized Optional<WikiExternalIdentity> link(long userId, ExternalAuthProvider provider,
                                                             ExternalAuthUser profile) {
        String providerId = normalizeProvider(provider.id());
        String subject = profile.subject();
        if (providerId.isEmpty() || subject.isBlank()) {
            return Optional.empty();
        }
        String key = bindingKey(providerId, subject);
        WikiExternalIdentity existing = mapper.selectOne(Wrappers.<WikiExternalIdentity>lambdaQuery()
                .eq(WikiExternalIdentity::getBindingKey, key)
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            if (!existing.getUserId().equals(userId)) {
                return Optional.empty();
            }
            existing.setUsername(profile.username());
            existing.setEmail(profile.email());
            existing.setAvatar(profile.avatarUrl());
            existing.setLastLoginAt(now);
            mapper.updateById(existing);
            return Optional.of(existing);
        }
        WikiExternalIdentity identity = new WikiExternalIdentity();
        identity.setUserId(userId);
        identity.setProvider(providerId);
        identity.setSubject(subject);
        identity.setBindingKey(key);
        identity.setUsername(profile.username());
        identity.setEmail(profile.email());
        identity.setAvatar(profile.avatarUrl());
        identity.setLinkedAt(now);
        identity.setLastLoginAt(now);
        try {
            mapper.insert(identity);
        } catch (RuntimeException exception) {
            // 唯一约束兜底：并发绑定同一个第三方账号时，重新读出后按冲突处理。
            WikiExternalIdentity raced = mapper.selectOne(Wrappers.<WikiExternalIdentity>lambdaQuery()
                    .eq(WikiExternalIdentity::getBindingKey, key)
                    .last("LIMIT 1"));
            if (raced == null || !raced.getUserId().equals(userId)) {
                return Optional.empty();
            }
            return Optional.of(raced);
        }
        return Optional.of(identity);
    }

    public void updateAfterLogin(WikiExternalIdentity identity, ExternalAuthUser profile) {
        if (identity == null) {
            return;
        }
        identity.setUsername(profile.username());
        identity.setEmail(profile.email());
        identity.setAvatar(profile.avatarUrl());
        identity.setLastLoginAt(LocalDateTime.now());
        mapper.updateById(identity);
    }

    /** 解绑；返回是否真的删除了绑定。 */
    public boolean unlink(long userId, String provider) {
        return mapper.delete(Wrappers.<WikiExternalIdentity>lambdaQuery()
                .eq(WikiExternalIdentity::getUserId, userId)
                .eq(WikiExternalIdentity::getProvider, normalizeProvider(provider))) > 0;
    }

    /** provider + subject 的不可逆唯一键（subject 不直接进唯一索引）。 */
    static String bindingKey(String provider, String subject) {
        String providerId = normalizeProvider(provider);
        String value = subject == null ? "" : subject.trim();
        if (providerId.isEmpty() || value.isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return providerId + ":" + HEX.formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String normalizeProvider(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }
}

