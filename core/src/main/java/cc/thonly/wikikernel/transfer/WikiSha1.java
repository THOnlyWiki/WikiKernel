package cc.thonly.wikikernel.transfer;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 版本正文的 SHA-1（base36），与 MediaWiki 导出 XML 的 {@code sha1} 字段一致
 * （MediaWiki 用 {@code base_convert(sha1($text), 16, 36)}，无左侧补零）。
 */
public final class WikiSha1 {

    private WikiSha1() {
    }

    /** 正文的 SHA-1（UTF-8）转 base36 小写字符串。 */
    public static String base36(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, hash).toString(36);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 不可用", exception);
        }
    }

    /** 正文的 UTF-8 字节数（MediaWiki 导出 XML 的 {@code bytes} 字段）。 */
    public static int bytes(String content) {
        return (content == null ? "" : content).getBytes(StandardCharsets.UTF_8).length;
    }
}
