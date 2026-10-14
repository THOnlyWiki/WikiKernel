package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件平台桥：{@link UserSettings}（已知列 + 通用键值表）与 {@link CurrentUser}（无请求上下文 = 匿名）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class PluginUserBridgeTest {

    @Autowired
    private UserSettings userSettings;

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void knownColumnPreferencesRoundTrip() {
        WikiUser user = insertUser();
        assertEquals("", userSettings.get(user.getId(), "gadgets", ""));
        userSettings.set(user.getId(), "gadgets", "DarkMode,QuickEdit");
        assertEquals("DarkMode,QuickEdit", userSettings.get(user.getId(), "gadgets", ""));
        // 直接读实体列确认落库（Gadgets 插件的偏好就在 wiki_user.gadgets）
        assertEquals("DarkMode,QuickEdit", wikiUserMapper.selectById(user.getId()).getGadgets());

        // 数值字段：读写都按实体列
        userSettings.set(user.getId(), "rcLimit", "250");
        assertEquals("250", userSettings.get(user.getId(), "rcLimit", "0"));
        assertEquals(250, wikiUserMapper.selectById(user.getId()).getRcLimit());

        // 国家 / 地区（ISO 3166-1 代码）：写入统一转大写，空串 = 未指定
        assertEquals("", userSettings.get(user.getId(), "country", ""));
        userSettings.set(user.getId(), "country", "tw");
        assertEquals("TW", userSettings.get(user.getId(), "country", ""));
        assertEquals("TW", wikiUserMapper.selectById(user.getId()).getCountry());
        userSettings.set(user.getId(), "country", "");
        assertEquals("", userSettings.get(user.getId(), "country", ""));
    }

    @Test
    void unknownKeysUseGenericPreferenceTable() {
        WikiUser user = insertUser();
        assertEquals("默认值", userSettings.get(user.getId(), "gadget-panel.expanded", "默认值"));
        userSettings.set(user.getId(), "gadget-panel.expanded", "1");
        assertEquals("1", userSettings.get(user.getId(), "gadget-panel.expanded", ""));

        Map<String, String> listed = userSettings.list(user.getId(), "gadget-panel.");
        assertEquals("1", listed.get("gadget-panel.expanded"));
        assertTrue(userSettings.list(user.getId(), "other.").isEmpty());

        // 清空 = 值变为空串（键保留，方便插件区分「没写过」与「写空」）
        userSettings.set(user.getId(), "gadget-panel.expanded", "");
        assertEquals("", userSettings.get(user.getId(), "gadget-panel.expanded", "默认值"));
    }

    @Test
    void currentUserIsAnonymousWithoutRequestContext() {
        Viewer viewer = currentUser.viewer();
        assertTrue(viewer.anonymous());
        assertEquals(0, currentUser.id());
        assertEquals("", currentUser.name());
        assertTrue(!currentUser.loggedIn());
    }

    private WikiUser insertUser() {
        WikiUser user = new WikiUser();
        user.setUsername("偏好桥用户" + System.nanoTime());
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }
}
