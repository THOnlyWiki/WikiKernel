package cc.thonly.wikikernel.plugin.math;

import cc.thonly.wikikernel.common.service.SiteConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MathHeadProvider}：前端开关与脚本注入、功能关闭时不注入。
 * <p>
 * 站点级注入（{@code siteWide()}）保证 {@code window.WIKI_CONFIG.math} 在 core 初始化
 * {@code window.WIKI_CONFIG} 之后写入——否则会被页头初始化覆盖，前端脚本提前退出，
 * 公式只剩 TeX 源码回退（本次修复的回归测试）。
 */
class MathHeadProviderTest {

    private static SiteConfig config(boolean enabled) {
        return new SiteConfig() {
            @Override
            public String get(String key, String defaultValue) {
                return defaultValue;
            }

            @Override
            public boolean getBoolean(String key, boolean defaultValue) {
                return enabled;
            }
        };
    }

    @Test
    void injectsSwitchAndScriptWhenEnabled() {
        MathHeadProvider provider = new MathHeadProvider(config(true));
        assertTrue(provider.siteWide(), "开关要在 core 初始化 window.WIKI_CONFIG 之后注入");
        List<String> items = provider.headItems(null);
        String joined = String.join("\n", items);
        assertTrue(joined.contains(
                "WIKI_CONFIG = Object.assign(window.WIKI_CONFIG || {}, {math: true})"), joined);
        assertTrue(joined.contains("/plugins/math/math.js"), joined);
    }

    @Test
    void injectsNothingWhenDisabled() {
        assertEquals(List.of(), new MathHeadProvider(config(false)).headItems(null));
    }
}
