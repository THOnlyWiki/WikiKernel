package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.user.MediaWikiPassword;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 从 MediaWiki 迁移过来的密码哈希：能登录，并在登录成功后自动升级为当前默认格式。
 *
 * <ul>
 *     <li>无前缀 MD5（1.24 之前，盐 = 用户 ID）：登录成功 → 升级；</li>
 *     <li>{@code :A:} / {@code :B:} / 旧参数 {@code :pbkdf2:}：登录成功 → 升级；</li>
 *     <li>已经是当前默认格式的哈希：登录后保持不变；</li>
 *     <li>错误密码不会触发升级。</li>
 * </ul>
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class PasswordMigrationTest {

    private static final String PASSWORD = "secret123";

    @Autowired
    private UserService userService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void legacyBareMd5IsUpgradedOnLogin() {
        WikiUser user = insertUser("迁移无盐MD5" + System.nanoTime(), md5(PASSWORD));
        assertNotNull(userService.login(user.getUsername(), PASSWORD));
        assertTrue(storedPassword(user).startsWith(":pbkdf2:sha512:30000:64:"), storedPassword(user));
        // 升级后仍然能登录
        assertNotNull(userService.login(user.getUsername(), PASSWORD));
        assertNull(userService.login(user.getUsername(), "wrong"));
    }

    @Test
    void legacyUserIdSaltedMd5IsUpgradedOnLogin() {
        WikiUser user = insertUser("迁移旧式MD5" + System.nanoTime(), "placeholder");
        user.setPassword(md5(user.getId() + "-" + md5(PASSWORD)));
        wikiUserMapper.updateById(user);

        assertNotNull(userService.login(user.getUsername(), PASSWORD));
        assertTrue(storedPassword(user).startsWith(":pbkdf2:sha512:30000:64:"), storedPassword(user));
    }

    @Test
    void mediaWikiTypeAAndBAreUpgradedOnLogin() {
        WikiUser typeA = insertUser("迁移A" + System.nanoTime(), ":A:" + md5(PASSWORD));
        assertNotNull(userService.login(typeA.getUsername(), PASSWORD));
        assertTrue(storedPassword(typeA).startsWith(":pbkdf2:sha512:30000:64:"));

        WikiUser typeB = insertUser("迁移B" + System.nanoTime(),
                ":B:1b2c3d4e:" + md5("1b2c3d4e-" + md5(PASSWORD)));
        assertNotNull(userService.login(typeB.getUsername(), PASSWORD));
        assertTrue(storedPassword(typeB).startsWith(":pbkdf2:sha512:30000:64:"));
    }

    @Test
    void olderPbkdf2ParamsAreUpgradedOnLogin() {
        String salt = "AAECAwQFBgcICQoLDA0ODw==";
        byte[] digest = pbkdf2Sha512(PASSWORD, salt);
        String hash = ":pbkdf2:sha512:1000:64:" + salt + ":"
                + java.util.Base64.getEncoder().encodeToString(digest);
        WikiUser user = insertUser("迁移旧参数" + System.nanoTime(), hash);

        assertNotNull(userService.login(user.getUsername(), PASSWORD));
        assertTrue(storedPassword(user).startsWith(":pbkdf2:sha512:30000:64:"), storedPassword(user));
    }

    @Test
    void currentFormatIsKeptAndWrongPasswordDoesNotUpgrade() {
        String hash = MediaWikiPassword.hash(PASSWORD);
        WikiUser user = insertUser("不需要升级" + System.nanoTime(), hash);

        // 错误密码：不通过、也不升级
        assertNull(userService.login(user.getUsername(), "wrong"));
        assertEquals(hash, storedPassword(user));

        // 正确密码：通过，哈希保持不变
        assertNotNull(userService.login(user.getUsername(), PASSWORD));
        assertEquals(hash, storedPassword(user));
    }

    // ==================== 工具 ====================

    private WikiUser insertUser(String username, String passwordHash) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword(passwordHash);
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }

    private String storedPassword(WikiUser user) {
        return userService.findById(user.getId()).orElseThrow().getPassword();
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] pbkdf2Sha512(String password, String saltBase64) {
        try {
            javax.crypto.SecretKeyFactory factory =
                    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    password.toCharArray(), java.util.Base64.getDecoder().decode(saltBase64), 1000, 64 * 8);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
