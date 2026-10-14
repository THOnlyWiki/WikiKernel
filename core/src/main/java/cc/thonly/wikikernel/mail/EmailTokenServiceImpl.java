package cc.thonly.wikikernel.mail;

import cc.thonly.wikikernel.common.data.WikiEmailToken;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiEmailTokenMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

@Service
public class EmailTokenServiceImpl implements EmailTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WikiEmailTokenMapper emailTokenMapper;

    public EmailTokenServiceImpl(WikiEmailTokenMapper emailTokenMapper) {
        this.emailTokenMapper = emailTokenMapper;
    }

    @Override
    public String issue(long userId, String type, String email, int expireHours) {
        revokeAll(userId, type);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        WikiEmailToken token = new WikiEmailToken();
        token.setUserId(userId);
        token.setType(type);
        token.setEmail(Strings.trimToEmpty(email));
        token.setTokenHash(sha256Hex(raw));
        LocalDateTime now = LocalDateTime.now();
        token.setCreatedAt(now);
        token.setExpiresAt(now.plusHours(Math.max(1, expireHours)));
        emailTokenMapper.insert(token);
        return raw;
    }

    @Override
    public Optional<WikiEmailToken> resolve(String rawToken) {
        String value = Strings.trimToEmpty(rawToken);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        WikiEmailToken token = emailTokenMapper.selectOne(Wrappers.<WikiEmailToken>lambdaQuery()
                .eq(WikiEmailToken::getTokenHash, sha256Hex(value))
                .last("LIMIT 1"));
        if (token == null) {
            return Optional.empty();
        }
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(LocalDateTime.now())) {
            emailTokenMapper.deleteById(token.getId());
            return Optional.empty();
        }
        return Optional.of(token);
    }

    @Override
    public void consume(WikiEmailToken token) {
        if (token != null && token.getId() != null) {
            emailTokenMapper.deleteById(token.getId());
        }
    }

    @Override
    public void revokeAll(long userId, String type) {
        emailTokenMapper.delete(Wrappers.<WikiEmailToken>lambdaQuery()
                .eq(WikiEmailToken::getUserId, userId)
                .eq(WikiEmailToken::getType, Strings.trimToEmpty(type)));
    }

    @Override
    public Optional<LocalDateTime> lastIssuedAt(long userId, String type) {
        WikiEmailToken token = emailTokenMapper.selectOne(Wrappers.<WikiEmailToken>lambdaQuery()
                .eq(WikiEmailToken::getUserId, userId)
                .eq(WikiEmailToken::getType, Strings.trimToEmpty(type))
                .orderByDesc(WikiEmailToken::getCreatedAt)
                .last("LIMIT 1"));
        return token == null ? Optional.empty() : Optional.ofNullable(token.getCreatedAt());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
