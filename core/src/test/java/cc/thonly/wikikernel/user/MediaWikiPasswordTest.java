package cc.thonly.wikikernel.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MediaWikiPassword}：与 MediaWiki 密码哈希格式的兼容性测试。
 *
 * <p>固定向量由 Python（{@code hashlib} / {@code hmac} / {@code bcrypt}）独立生成后写入，
 * 校验 Java 实现与 PHP（MediaWiki）结果一致：
 * <ul>
 *     <li>盐 {@code AAECAwQFBgcICQoLDA0ODw==}（16 字节 0x00…0x0f）；</li>
 *     <li>密码 {@code secret123} 的几种代表性哈希；</li>
 * </ul>
 */
class MediaWikiPasswordTest {

    private static final String PASSWORD = "secret123";

    /** 16 字节固定盐（Python: base64(bytes(range(16)))）。 */
    private static final String SALT = "AAECAwQFBgcICQoLDA0ODw==";

    /** :pbkdf2:sha512:30000:64:<固定盐>:<哈希>（MediaWiki 当前默认参数）。 */
    private static final String PBKDF2_DEFAULT =
            ":pbkdf2:sha512:30000:64:" + SALT
                    + ":rxnEUACPX9lc3ES/vigBXrJVCj2A0qJQwPbAnKwnDKf0CQ8PfzlPt0QUTXYZtyUP+AC/rLKxTo/vTMWlALQJBw==";

    /** 旧参数（sha256 / 10000 轮）：能校验但需要升级。 */
    private static final String PBKDF2_OLD_PARAMS =
            ":pbkdf2:sha256:10000:64:" + SALT
                    + ":/UyT7Q0X6uGlcgU8OaaC4wqsrzqSGDpJglC2KXuMhtyShETyPkpsD2Par6o/NtSNSKhvzVFjte52GUX6uoaVyw==";

    /** 无盐 MD5。 */
    private static final String TYPE_A = ":A:5d7845ac6ee7cfffafc5fe5f35cf666d";

    /** 1.24 之前无前缀格式（1.24 默认盐 = 用户 ID，测试用 ID = 7）。 */
    private static final String LEGACY_PLAIN = "5d7845ac6ee7cfffafc5fe5f35cf666d";
    private static final String LEGACY_SALTED_BY_USER_ID = "5f53f505ce3bdf35a57daf1ec5af980c";

    /** 加盐 MD5：:B:<盐>:<md5(盐 + '-' + md5(密码))>。 */
    private static final String TYPE_B = ":B:1b2c3d4e:125c16f2f963be9e7866924e107e3e91";

    /** 分层格式：先 B 再 PBKDF2。 */
    private static final String LEGACY_B =
            ":pbkdf2-legacyB:!sha512:30000:64!1b2c3d4e!" + SALT
                    + "!YW9XMazL+aA8VkKTqCiyJvlqiFqcc/qRXUO6Bq55Sq+Muxy201mrzkChZdWO/DY11sdSIr+JU2kS477BrVnbOg==";

    /** 分层格式：先 A 再 PBKDF2。 */
    private static final String LEGACY_A =
            ":pbkdf2-legacyA:!sha512:30000:64!!" + SALT
                    + "!dp9VBqP70vN7CblS0gkU7Gj2oFhPML5FjAjVdmIRRxX7rEqSWEgXLN7IlmsA2AWkcmvbghdtAR6wDj0fh5T4Fg==";

    /** bcrypt：:bcrypt:<代价>$<22 位盐>$<31 位哈希>（Python bcrypt 生成，密码 password）。 */
    private static final String BCRYPT = ":bcrypt:10$N9qo8uLOickgx2ZMRZoMye$8fOsiTWZqYtkxvXkKm8BMzjT7t/vIdq";

    @Test
    void hashesWithMediaWikiDefaultFormat() {
        String hash = MediaWikiPassword.hash(PASSWORD);
        assertTrue(hash.startsWith(":pbkdf2:sha512:30000:64:"), hash);
        assertTrue(MediaWikiPassword.verify(PASSWORD, hash));
        assertFalse(MediaWikiPassword.verify("wrong", hash));
        assertFalse(MediaWikiPassword.needsRehash(hash), "新哈希不需要升级");
        assertTrue(MediaWikiPassword.isRecognizedFormat(hash));
        // 每次随机盐：两次哈希不同，但都能校验
        String other = MediaWikiPassword.hash(PASSWORD);
        assertNotEquals(hash, other);
        assertTrue(MediaWikiPassword.verify(PASSWORD, other));
    }

    @Test
    void verifiesPbkdf2Vectors() {
        assertTrue(MediaWikiPassword.verify(PASSWORD, PBKDF2_DEFAULT));
        assertFalse(MediaWikiPassword.needsRehash(PBKDF2_DEFAULT));
        assertTrue(MediaWikiPassword.verify(PASSWORD, PBKDF2_OLD_PARAMS));
        assertTrue(MediaWikiPassword.needsRehash(PBKDF2_OLD_PARAMS), "旧参数（sha256/10000）应升级");
    }

    @Test
    void verifiesLegacyMd5Formats() {
        assertTrue(MediaWikiPassword.verify(PASSWORD, TYPE_A));
        assertTrue(MediaWikiPassword.verify(PASSWORD, LEGACY_PLAIN));
        // 无用户 ID 时无法校验「盐 = 用户 ID」的旧格式；传入 ID 后可以
        assertFalse(MediaWikiPassword.verify(PASSWORD, LEGACY_SALTED_BY_USER_ID));
        assertTrue(MediaWikiPassword.verify(PASSWORD, LEGACY_SALTED_BY_USER_ID, 7L));
        assertTrue(MediaWikiPassword.verify(PASSWORD, TYPE_B));
        // 历史 bug：加盐哈希被写成 :A:<盐>:<哈希>（MediaWiki 新版仍接受）
        assertTrue(MediaWikiPassword.verify(PASSWORD, ":A:1b2c3d4e:125c16f2f963be9e7866924e107e3e91"));
        // 大小写不敏感（存储的十六进制按小写比较）
        assertTrue(MediaWikiPassword.verify(PASSWORD, ":A:5D7845AC6EE7CFFFAFC5FE5F35CF666D"));

        assertTrue(MediaWikiPassword.needsRehash(TYPE_A));
        assertTrue(MediaWikiPassword.needsRehash(LEGACY_PLAIN));
        assertTrue(MediaWikiPassword.needsRehash(TYPE_B));
    }

    @Test
    void verifiesLayeredFormats() {
        assertTrue(MediaWikiPassword.verify(PASSWORD, LEGACY_B));
        assertTrue(MediaWikiPassword.needsRehash(LEGACY_B));
        assertTrue(MediaWikiPassword.verify(PASSWORD, LEGACY_A));
        assertFalse(MediaWikiPassword.verify("wrong", LEGACY_A));
    }

    @Test
    void verifiesBcrypt() {
        assertTrue(MediaWikiPassword.verify("password", BCRYPT));
        assertFalse(MediaWikiPassword.verify("password1", BCRYPT));
        assertTrue(MediaWikiPassword.needsRehash(BCRYPT));
    }

    @Test
    void verifiesNonAsciiPasswords() {
        String password = "密码123";
        assertTrue(MediaWikiPassword.verify(password, ":A:d18902dd4cc8df459a9e769dc749888d"));
        assertTrue(MediaWikiPassword.verify(password,
                ":pbkdf2:sha512:30000:64:" + SALT
                        + ":v5TiOpen1NiXdw4tNMApGte+JBEaNwJALjFcSvqlgSWBlWNhzaOVuhVx1GaMqGnL1rAjhUcMJsw/sSaPf/nLxg=="));
        // 生成的哈希同样可校验（UTF-8 往返）
        assertTrue(MediaWikiPassword.verify(password, MediaWikiPassword.hash(password)));
    }

    @Test
    void rejectsUnknownOrMalformedHashes() {
        assertFalse(MediaWikiPassword.verify(PASSWORD, null));
        assertFalse(MediaWikiPassword.verify(PASSWORD, ""));
        assertFalse(MediaWikiPassword.verify(PASSWORD, "  "));
        assertFalse(MediaWikiPassword.verify(PASSWORD, "not-a-hash"));
        assertFalse(MediaWikiPassword.verify(PASSWORD, ":"));
        assertFalse(MediaWikiPassword.verify(PASSWORD, ":argon2:$argon2id$v=19$m=65536,t=4,p=1$c2FsdA$aGFzaA"));
        assertFalse(MediaWikiPassword.verify(PASSWORD, ":mysql:abcdef"));
        assertFalse(MediaWikiPassword.verify(PASSWORD, ":B:1b2c3d4e"));
        assertFalse(MediaWikiPassword.verify(PASSWORD, ":pbkdf2:sha512:30000:64:not-base64!:not-base64!"));
        assertFalse(MediaWikiPassword.verify(PASSWORD, ":pbkdf2:sha512:0:64:" + SALT + ":AAAA"));
        assertFalse(MediaWikiPassword.verify(PASSWORD, ":pbkdf2:sha512:30000:64:" + SALT + ":AAAA"));

        assertFalse(MediaWikiPassword.isRecognizedFormat(null));
        assertFalse(MediaWikiPassword.isRecognizedFormat("not-a-hash"));
        assertFalse(MediaWikiPassword.isRecognizedFormat(":argon2:xxx"));
        assertFalse(MediaWikiPassword.isRecognizedFormat(":B:only-salt"));
        assertTrue(MediaWikiPassword.isRecognizedFormat(TYPE_A));
        assertTrue(MediaWikiPassword.isRecognizedFormat(LEGACY_PLAIN));
        assertTrue(MediaWikiPassword.isRecognizedFormat(TYPE_B));
        assertTrue(MediaWikiPassword.isRecognizedFormat(LEGACY_B));
        assertTrue(MediaWikiPassword.isRecognizedFormat(BCRYPT));
    }

    @Test
    void needsRehashEdgeCases() {
        // 空 / 畸形数据不触发升级（登录本来就不会成功）
        assertFalse(MediaWikiPassword.needsRehash(null));
        assertFalse(MediaWikiPassword.needsRehash(""));
        assertFalse(MediaWikiPassword.needsRehash("  "));
        assertFalse(MediaWikiPassword.isRecognizedFormat("  "));
        // 其它类型（认识与否）统一按「需要升级」处理
        assertTrue(MediaWikiPassword.needsRehash(":argon2:xxx"));
    }
}
