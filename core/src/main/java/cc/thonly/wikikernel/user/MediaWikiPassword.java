package cc.thonly.wikikernel.user;

import org.springframework.security.crypto.bcrypt.BCrypt;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MediaWiki 兼容的密码哈希（对应 MediaWiki 的 {@code PasswordFactory} / {@code $wgPasswordConfig}）。
 *
 * <p>新密码一律按 MediaWiki 默认配置生成：
 * {@code :pbkdf2:sha512:30000:64:<base64 盐>:<base64 哈希>}（{@code $wgPasswordDefault = 'pbkdf2'}）。
 *
 * <p>校验兼容 MediaWiki 各代格式，从 MediaWiki 迁移过来的 {@code user_password} 可以原样写入
 * {@code wiki_user.password}：
 * <ul>
 *     <li>{@code :A:<md5(密码)>}：无盐 MD5（{@code $wgPasswordSalt = false} 的老站点）；</li>
 *     <li>无前缀 32 位十六进制 MD5：1.24 之前的格式（默认 {@code $wgPasswordSalt = true} 时盐 = 用户 ID，
 *     即 {@code md5(用户ID + '-' + md5(密码))}，校验时用 {@link #verify(String, String, Long)} 传入用户 ID；
 *     {@code $wgPasswordSalt = false} 时就是 {@code md5(密码)}，两种都会尝试）；</li>
 *     <li>{@code :B:<盐>:<md5(盐 + '-' + md5(密码))>}：加盐 MD5；</li>
 *     <li>{@code :pbkdf2:<算法>:<轮数>:<长度>:<盐>:<哈希>}：PBKDF2（sha1 / sha256 / sha512）；</li>
 *     <li>{@code :pbkdf2-legacyA:…} / {@code :pbkdf2-legacyB:…}：分层格式（先 A / B，再对结果跑 PBKDF2）；</li>
 *     <li>{@code :bcrypt:<代价>$<22 位盐>$<31 位哈希>}：bcrypt（{@code $2y$}）。</li>
 * </ul>
 *
 * <p>不认识 / 不支持的格式（如 {@code :argon2:}、{@code :mysql:}）校验一律返回 false；
 * {@link #needsRehash(String)} 为 true 的哈希由调用方在登录成功后升级为新默认格式（与 MediaWiki 一致）。
 */
public final class MediaWikiPassword {

    /** 新哈希使用的类型（与 MediaWiki {@code $wgPasswordDefault} 默认值一致）。 */
    public static final String DEFAULT_TYPE = "pbkdf2";

    /** 新哈希使用的摘要算法（与 MediaWiki {@code $wgPasswordConfig['pbkdf2']['algo']} 默认值一致）。 */
    public static final String DEFAULT_ALGO = "sha512";

    /** 新哈希使用的迭代轮数（与 MediaWiki {@code $wgPasswordConfig['pbkdf2']['cost']} 默认值一致）。 */
    public static final int DEFAULT_ROUNDS = 30000;

    /** 新哈希输出的字节长度（与 MediaWiki {@code $wgPasswordConfig['pbkdf2']['length']} 默认值一致）。 */
    public static final int DEFAULT_LENGTH = 64;

    /** 随机盐长度（字节）：对应 MediaWiki 的 {@code base64_encode(random_bytes(16))}。 */
    private static final int SALT_BYTES = 16;

    /** 校验时允许的迭代轮数上限（防止异常哈希把登录 CPU 拖死；MediaWiki 默认 30000）。 */
    private static final int MAX_ROUNDS = 10_000_000;

    /** 校验时允许的哈希字节长度上限。 */
    private static final int MAX_LENGTH = 1024;

    private static final Pattern BARE_MD5 = Pattern.compile("^[0-9a-f]{32}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private MediaWikiPassword() {
    }

    /** 生成 MediaWiki 默认格式的密码哈希（随机 16 字节盐）。 */
    public static String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        String saltBase64 = Base64.getEncoder().encodeToString(salt);
        String hashBase64;
        try {
            hashBase64 = Base64.getEncoder()
                    .encodeToString(pbkdf2(DEFAULT_ALGO, rawPassword, salt, DEFAULT_ROUNDS, DEFAULT_LENGTH));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        }
        return ":" + DEFAULT_TYPE + ":" + DEFAULT_ALGO + ":" + DEFAULT_ROUNDS + ":" + DEFAULT_LENGTH
                + ":" + saltBase64 + ":" + hashBase64;
    }

    /** 校验密码（不含用户 ID 的旧格式哈希请用 {@link #verify(String, String, Long)}）。 */
    public static boolean verify(String rawPassword, String storedHash) {
        return verify(rawPassword, storedHash, null);
    }

    /**
     * 校验密码。
     *
     * @param rawPassword 用户输入的明文密码
     * @param storedHash  数据库里存的哈希（MediaWiki 各代格式均可）
     * @param userId      用户行 ID：只有「无前缀 MD5」这种 1.24 之前的极老格式才需要
     *                    （旧站点默认拿用户 ID 当盐）；不迁移旧格式时可以传 null
     */
    public static boolean verify(String rawPassword, String storedHash, Long userId) {
        if (rawPassword == null || storedHash == null) {
            return false;
        }
        String stored = storedHash.trim();
        if (stored.isEmpty()) {
            return false;
        }
        try {
            if (BARE_MD5.matcher(stored).matches()) {
                // 1.24 之前的无前缀格式：$wgPasswordSalt = false 时是 md5(密码)，
                // 默认情况下是 md5(用户ID + '-' + md5(密码))，两种都试（MediaWiki 新版只按后者兼容）
                if (equalsHex(md5(rawPassword), stored)) {
                    return true;
                }
                return userId != null && equalsHex(md5(userId + "-" + md5(rawPassword)), stored);
            }
            if (stored.charAt(0) != ':') {
                return false;
            }
            String[] parts = stored.split(":", -1);
            return switch (parts[1]) {
                // :A:<md5>；历史上 MediaWiki 的一个 bug 会把加盐哈希写成 :A:<盐>:<哈希>，新版仍接受
                case "A" -> parts.length == 3
                        ? equalsHex(md5(rawPassword), parts[2])
                        : parts.length == 4 && equalsHex(md5(parts[2] + "-" + md5(rawPassword)), parts[3]);
                case "B" -> parts.length == 4 && equalsHex(md5(parts[2] + "-" + md5(rawPassword)), parts[3]);
                case "pbkdf2" -> verifyPbkdf2(rawPassword, parts);
                case "pbkdf2-legacyA" -> verifyLayered(rawPassword, false, stored);
                case "pbkdf2-legacyB" -> verifyLayered(rawPassword, true, stored);
                case "bcrypt" -> verifyBcrypt(rawPassword, stored);
                default -> false;
            };
        } catch (RuntimeException | GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * 哈希是否需要用当前默认格式重写（登录成功后自动升级旧格式用）。
     * 无前缀 MD5、{@code :A:} / {@code :B:} / 分层 / {@code :bcrypt:}，
     * 以及参数（算法 / 轮数 / 长度）与当前默认不一致的 {@code :pbkdf2:} 都算需要升级。
     */
    public static boolean needsRehash(String storedHash) {
        if (storedHash == null) {
            return false;
        }
        String stored = storedHash.trim();
        if (stored.isEmpty()) {
            return false;
        }
        if (stored.charAt(0) != ':') {
            return true;
        }
        // :pbkdf2:<算法>:<轮数>:<长度>:<盐>:<哈希>
        String[] parts = stored.split(":", -1);
        if (parts.length != 7 || !DEFAULT_TYPE.equals(parts[1])) {
            return true;
        }
        return !DEFAULT_ALGO.equals(parts[2])
                || parseInt(parts[3], -1) != DEFAULT_ROUNDS
                || parseInt(parts[4], -1) != DEFAULT_LENGTH;
    }

    /** 是否为「能认出来的密码哈希」（安装向导 / 配置校验用）——能认出来不代表一定可校验（如 argon2）。 */
    public static boolean isRecognizedFormat(String storedHash) {
        if (storedHash == null) {
            return false;
        }
        String stored = storedHash.trim();
        if (stored.isEmpty()) {
            return false;
        }
        if (BARE_MD5.matcher(stored).matches()) {
            return true;
        }
        if (stored.charAt(0) != ':') {
            return false;
        }
        String[] parts = stored.split(":", -1);
        if (parts.length < 2) {
            return false;
        }
        return switch (parts[1]) {
            case "A" -> parts.length == 3 || parts.length == 4;
            case "B" -> parts.length == 4;
            case "pbkdf2" -> parts.length == 7;
            case "pbkdf2-legacyA", "pbkdf2-legacyB" -> stored.contains("!");
            case "bcrypt" -> stored.indexOf('$', 1) > 0;
            default -> false;
        };
    }

    // ==================== 各格式校验 ====================

    private static boolean verifyPbkdf2(String rawPassword, String[] parts) throws GeneralSecurityException {
        // :pbkdf2:<算法>:<轮数>:<长度>:<base64 盐>:<base64 哈希>
        if (parts.length != 7) {
            return false;
        }
        return pbkdf2Matches(rawPassword, parts[2], parseInt(parts[3], 0), parts[4], parts[5], parts[6]);
    }

    /** 分层格式：第一层是 A / B（{@code salted} = 是否 B），第二层是 PBKDF2。 */
    private static boolean verifyLayered(String rawPassword, boolean salted, String stored)
            throws GeneralSecurityException {
        // :pbkdf2-legacyB:!<PBKDF2 参数>!<B 盐或空>!<base64 盐>!<base64 哈希>
        // :pbkdf2-legacyA:!<PBKDF2 参数>!!<base64 盐>!<base64 哈希>
        String[] fields = stored.split("!", -1);
        if (fields.length != 5) {
            return false;
        }
        String innerHash = salted ? md5(fields[2] + "-" + md5(rawPassword)) : md5(rawPassword);
        String[] params = fields[1].split(":", -1);
        if (params.length != 3) {
            return false;
        }
        return pbkdf2Matches(innerHash, params[0], parseInt(params[1], 0), params[2], fields[3], fields[4]);
    }

    /** 校验一层 PBKDF2：盐 / 哈希均为 base64，{@code input} 是原始密码或上一层哈希（hex）。 */
    private static boolean pbkdf2Matches(String input, String algo, int rounds, String lengthText,
                                         String saltBase64, String hashBase64) throws GeneralSecurityException {
        int length = parseInt(lengthText, 0);
        if (rounds <= 0 || rounds > MAX_ROUNDS || length <= 0 || length > MAX_LENGTH) {
            return false;
        }
        byte[] salt = Base64.getDecoder().decode(saltBase64);
        byte[] expected = Base64.getDecoder().decode(hashBase64);
        if (expected.length != length) {
            // 长度字段与实际哈希不符：MediaWiki 算出的长度也对不上，同样算校验失败
            return false;
        }
        byte[] actual = pbkdf2(algo, input, salt, rounds, length);
        return MessageDigest.isEqual(expected, actual);
    }

    private static boolean verifyBcrypt(String rawPassword, String stored) {
        // :bcrypt:<代价>$<22 位盐>$<31 位哈希> —— 还原成标准 bcrypt（$2y$）再校验
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 3 || !parts[0].startsWith(":bcrypt:")) {
            return false;
        }
        int cost;
        try {
            cost = Integer.parseInt(parts[0].substring(":bcrypt:".length()));
        } catch (NumberFormatException e) {
            return false;
        }
        if (cost < 4 || cost > 31 || parts[1].length() != 22 || parts[2].length() != 31) {
            return false;
        }
        String standard = String.format(Locale.ROOT, "$2y$%02d$%s%s", cost, parts[1], parts[2]);
        try {
            return BCrypt.checkpw(rawPassword, standard);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ==================== 底层工具 ====================

    private static byte[] pbkdf2(String algo, String password, byte[] salt, int rounds, int length)
            throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(
                "PBKDF2WithHmac" + algo.toUpperCase(Locale.ROOT));
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, rounds, length * 8);
        try {
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xf, 16));
            builder.append(Character.forDigit(b & 0xf, 16));
        }
        return builder.toString();
    }

    private static boolean equalsHex(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
