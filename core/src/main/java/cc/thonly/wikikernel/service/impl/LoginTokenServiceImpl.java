package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiLoginToken;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.AuthProperties;
import cc.thonly.wikikernel.db.mapper.WikiLoginTokenMapper;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.LoginTokenService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 持久登录令牌实现：随机 selector + validator，数据库存 validator 的 SHA-256；
 * 校验用常量时间比较，成功刷新 {@code lastUsedAt}。
 */
@Service
public class LoginTokenServiceImpl implements LoginTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final WikiLoginTokenMapper tokenMapper;
    private final WikiUserMapper userMapper;
    private final AuthProperties properties;

    public LoginTokenServiceImpl(WikiLoginTokenMapper tokenMapper,
                                 WikiUserMapper userMapper,
                                 AuthProperties properties) {
        this.tokenMapper = tokenMapper;
        this.userMapper = userMapper;
        this.properties = properties;
    }

    @Override
    public String issue(long userId) {
        String selector = randomHex(16);
        String validator = randomHex(32);
        LocalDateTime now = LocalDateTime.now();
        WikiLoginToken token = new WikiLoginToken();
        token.setSelector(selector);
        token.setValidatorHash(sha256(validator));
        token.setUserId(userId);
        token.setCreatedAt(now);
        token.setLastUsedAt(now);
        token.setExpiresAt(properties.getTokenExpireDays() > 0
                ? now.plusDays(properties.getTokenExpireDays())
                : null);
        tokenMapper.insert(token);
        return selector + ":" + validator;
    }

    @Override
    public Optional<WikiUser> resolve(String cookieValue) {
        int colon = cookieValue == null ? -1 : cookieValue.indexOf(':');
        if (colon <= 0 || colon == cookieValue.length() - 1) {
            return Optional.empty();
        }
        String selector = cookieValue.substring(0, colon);
        String validator = cookieValue.substring(colon + 1);
        WikiLoginToken token = tokenMapper.selectOne(Wrappers.<WikiLoginToken>lambdaQuery()
                .eq(WikiLoginToken::getSelector, selector)
                .last("LIMIT 1"));
        if (token == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        // 旧版本默认“永不过期”（expiresAt=null）；启用有限有效期后，给旧令牌补上期限，
        // 避免历史令牌永远有效。
        if (token.getExpiresAt() == null && properties.getTokenExpireDays() > 0) {
            token.setExpiresAt(now.plusDays(properties.getTokenExpireDays()));
        }
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(now)) {
            tokenMapper.deleteById(token.getId());
            return Optional.empty();
        }
        if (!constantTimeEquals(sha256(validator), token.getValidatorHash())) {
            return Optional.empty();
        }
        token.setLastUsedAt(now);
        tokenMapper.updateById(token);
        return Optional.ofNullable(userMapper.selectById(token.getUserId()));
    }

    @Override
    public void revoke(String cookieValue) {
        int colon = cookieValue == null ? -1 : cookieValue.indexOf(':');
        if (colon <= 0) {
            return;
        }
        WikiLoginToken token = tokenMapper.selectOne(Wrappers.<WikiLoginToken>lambdaQuery()
                .eq(WikiLoginToken::getSelector, cookieValue.substring(0, colon))
                .last("LIMIT 1"));
        if (token != null) {
            tokenMapper.deleteById(token.getId());
        }
    }

    @Override
    public void revokeAll(long userId) {
        if (userId > 0) {
            tokenMapper.delete(Wrappers.<WikiLoginToken>lambdaQuery()
                    .eq(WikiLoginToken::getUserId, userId));
        }
    }

    // ==================== 内部实现 ====================

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HEX.formatHex(buffer);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(Strings.trimToEmpty(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }
}
