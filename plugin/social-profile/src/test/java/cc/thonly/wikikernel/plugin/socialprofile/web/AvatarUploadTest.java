package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileFakes;
import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 头像上传：规则（白名单 / 2MB / 替换旧文件）、接口语义（403 / 401 / 404 / 400 / 200）
 * 与头像文件服务（{@code GET /avatars/<文件名>}）。
 */
class AvatarUploadTest {

    @TempDir
    Path tempDir;

    @Test
    void storeValidatesTypeAndSizeAndReplacesOldFile() {
        SocialProfileFakes.FakeUsers users = new SocialProfileFakes.FakeUsers().add(7, "甲", "");
        SocialProfileFakes.FakeAvatars avatars = new SocialProfileFakes.FakeAvatars();
        AvatarSupport support = new AvatarSupport(avatars, users);
        byte[] png = "png-bytes".getBytes(StandardCharsets.UTF_8);

        String url = support.store(7, "me.PNG", "image/png", png);
        assertEquals("/avatars/u7-1.png", url);
        assertEquals("u7-1.png", avatars.current.get(7L));

        // 替换：新文件名、旧文件被删除（与迁移前一致）
        String second = support.store(7, "me.jpg", "image/jpeg", new byte[]{1, 2, 3});
        assertEquals("/avatars/u7-2.jpg", second);
        assertTrue(avatars.files.containsKey("u7-2.jpg"));
        assertTrue(!avatars.files.containsKey("u7-1.png"), "旧头像应被删除");

        // 非图片 / 空内容 / 过大：抛消息键，由接口本地化
        assertEquals("avatar.notImage", assertThrows(IllegalArgumentException.class,
                () -> support.store(7, "a.txt", "text/plain", new byte[]{1})).getMessage());
        assertEquals("avatar.empty", assertThrows(IllegalArgumentException.class,
                () -> support.store(7, "a.png", "image/png", new byte[0])).getMessage());
        avatars.maxBytes = 4;
        assertEquals("avatar.tooLarge", assertThrows(IllegalArgumentException.class,
                () -> support.store(7, "a.png", "image/png", new byte[]{1, 2, 3, 4, 5})).getMessage());
    }

    @Test
    void avatarUrlUsesUploadedFileOrDefault() {
        SocialProfileFakes.FakeUsers users = new SocialProfileFakes.FakeUsers()
                .add(7, "甲", "u7-9.png")
                .add(8, "乙", "");
        AvatarSupport support = new AvatarSupport(new SocialProfileFakes.FakeAvatars(), users);

        assertEquals("/avatars/u7-9.png", support.avatarUrl(7));
        assertEquals(UserStore.DEFAULT_AVATAR_URL, support.avatarUrl(8));
        assertEquals(UserStore.DEFAULT_AVATAR_URL, support.avatarUrl(0));
        assertEquals(UserStore.DEFAULT_AVATAR_URL, support.url(""));
    }

    @Test
    void siteDefaultAvatarAppliesToUsersWithoutUpload() {
        SocialProfileFakes.FakeUsers users = new SocialProfileFakes.FakeUsers()
                .add(7, "甲", "u7-9.png")
                .add(8, "乙", "")
                .defaultAvatar("/img/site-default.svg");
        AvatarSupport support = new AvatarSupport(new SocialProfileFakes.FakeAvatars(), users);

        assertEquals("/avatars/u7-9.png", support.avatarUrl(7));
        assertEquals("/img/site-default.svg", support.avatarUrl(8));
        assertEquals("/img/site-default.svg", support.avatarUrl(0));
        assertEquals("/img/site-default.svg", support.url(""));
    }

    @Test
    void uploadApiFollowsTheOldPermissionAndValidationRules() {
        SocialProfileFakes.FakeUsers users = new SocialProfileFakes.FakeUsers().add(7, "甲", "");
        SocialProfileFakes.FakeAvatars avatars = new SocialProfileFakes.FakeAvatars();
        AvatarSupport avatarSupport = new AvatarSupport(avatars, users);
        MockMultipartFile png = new MockMultipartFile("file", "me.png", "image/png",
                "png".getBytes(StandardCharsets.UTF_8));

        // 功能关闭 → 403（用户交互功能未启用。）
        AvatarApiController disabled = new AvatarApiController(avatarSupport,
                SocialProfileFakes.access(null), SocialProfileFakes.currentUser(
                SocialProfileFakes.viewer(7, "甲")), SocialProfileFakes.support(false));
        assertEquals(AjaxCode.FORBIDDEN, AjaxCode.fromCode(disabled.upload(png).getCode()).orElseThrow());

        // 匿名 → 401
        AvatarApiController anonymous = new AvatarApiController(avatarSupport,
                SocialProfileFakes.access(null),
                SocialProfileFakes.currentUser(Viewer.ANONYMOUS),
                SocialProfileFakes.support(true));
        assertEquals(AjaxCode.UNAUTHORIZED, AjaxCode.fromCode(anonymous.upload(png).getCode()).orElseThrow());

        // 被封禁 → 403 + 封禁说明
        AvatarApiController blocked = new AvatarApiController(avatarSupport,
                SocialProfileFakes.access("你已被封禁，不能上传头像。"),
                SocialProfileFakes.currentUser(SocialProfileFakes.viewer(7, "甲")),
                SocialProfileFakes.support(true));
        assertEquals("你已被封禁，不能上传头像。", blocked.upload(png).getMessage());

        // 账号不存在 → 404
        AvatarApiController missing = new AvatarApiController(avatarSupport,
                SocialProfileFakes.access(null),
                SocialProfileFakes.currentUser(SocialProfileFakes.viewer(99, "幽灵")),
                SocialProfileFakes.support(true));
        assertEquals(AjaxCode.NOT_FOUND, AjaxCode.fromCode(missing.upload(png).getCode()).orElseThrow());

        // 非图片 → 400 + 本地化文案（消息键 avatar.notImage）
        AvatarApiController controller = new AvatarApiController(avatarSupport,
                SocialProfileFakes.access(null),
                SocialProfileFakes.currentUser(SocialProfileFakes.viewer(7, "甲")),
                SocialProfileFakes.support(true));
        MockMultipartFile text = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        assertEquals("只能上传 PNG / JPEG / GIF / WebP 格式的图片。", controller.upload(text).getMessage());

        // 成功 → 200 + 头像 URL
        AjaxResult<Map<String, Object>> result = controller.upload(png);
        assertEquals(AjaxCode.SUCCESS, AjaxCode.fromCode(result.getCode()).orElseThrow());
        assertEquals("头像已更新。", result.getMessage());
        assertEquals("/avatars/u7-1.png", result.getData().get("url"));
        assertEquals(1, avatars.storeCalls);
    }

    @Test
    void avatarFileControllerServesStoredFilesAndHidesUnknownOnes() throws Exception {
        SocialProfileFakes.FakeAvatars avatars = new SocialProfileFakes.FakeAvatars();
        Path file = tempDir.resolve("u7-1.png");
        Files.write(file, "png".getBytes(StandardCharsets.UTF_8));
        avatars.paths.put("u7-1.png", file);
        AvatarFileController controller = new AvatarFileController(
                new AvatarSupport(avatars, new SocialProfileFakes.FakeUsers()));

        ResponseEntity<Resource> response = controller.avatar("u7-1.png");
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().toString().contains("image/png"));
        assertNotNull(response.getHeaders().getCacheControl());

        // 不存在 / 路径穿越 → 404（resolve 返回空）
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.avatar("missing.png")).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> controller.avatar("../../pom.xml")).getStatusCode().value());
    }
}
