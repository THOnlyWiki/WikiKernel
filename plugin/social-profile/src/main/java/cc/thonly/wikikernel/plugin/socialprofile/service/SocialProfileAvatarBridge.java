package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.externalauth.ExternalAuthAvatarBridge;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * SocialProfile 对第三方注册头像的桥：把 provider 返回的头像 URL 下载下来，
 * 复用插件自己的头像校验 / 落盘规则（{@link AvatarSupport} → {@link AvatarStore}）。
 * <p>
 * 只有 {@code features.social-profile} 开启时才工作；插件未加载时 core 找不到本桥，
 * 第三方注册仍正常完成。网络 / 校验失败只返回 {@code false}，不抛给注册流程。
 * <p>
 * 只允许 GitHub 使用的 HTTPS 头像域，避免把第三方 provider 的 URL 当成任意 SSRF 入口。
 */
public final class SocialProfileAvatarBridge implements ExternalAuthAvatarBridge {

    private static final Logger log = LoggerFactory.getLogger(SocialProfileAvatarBridge.class);

    /** 头像文件上限（与 AvatarStore 一致）；下载后先挡一次，避免把超大响应读进内存再落盘。 */
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private final AvatarSupport avatars;
    private final SocialProfileSupport support;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public SocialProfileAvatarBridge(AvatarSupport avatars, SocialProfileSupport support) {
        this.avatars = avatars;
        this.support = support;
    }

    @Override
    public boolean importFromUrl(long userId, String providerId, String avatarUrl) {
        if (userId <= 0 || !support.enabled() || !avatars.exists(userId)) {
            return false;
        }
        String url = Strings.trimToEmpty(avatarUrl);
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (!isAllowedAvatarUrl(uri)) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "WikiKernel")
                    .header("Accept", "image/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || !isAllowedAvatarUrl(response.uri())) {
                return false;
            }
            byte[] content = response.body();
            if (content == null || content.length == 0 || content.length > MAX_BYTES) {
                return false;
            }
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("image/")) {
                return false;
            }
            // AvatarStore 会按 contentType 推断扩展名（不接受 SVG / 其它可执行格式），
            // 并再次校验大小、写 wiki_user.avatar、删除旧头像。
            avatars.store(userId, "external-avatar", contentType, content);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            log.warn("第三方头像下载失败（provider={}, userId={}）：{}",
                    providerId, userId, exception.toString());
            return false;
        }
    }

    /** GitHub 的 avatar_url 指向 githubusercontent.com / github.com；只接受 HTTPS。 */
    private static boolean isAllowedAvatarUrl(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("github.com") || value.endsWith(".github.com")
                || value.equals("githubusercontent.com") || value.endsWith(".githubusercontent.com");
    }
}
