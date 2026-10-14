package cc.thonly.wikikernel.plugin.confirmedit;

import cc.thonly.wikikernel.common.extension.Viewer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站点设置语义：开关 {@code features.confirm-edit}（默认关闭）、免验证组
 * {@code features.confirm-edit.bypass-groups}（{@code |} / 换行分隔）与「谁需要验证码」。
 */
class ConfirmEditSettingsTest {

    private static final Viewer ANONYMOUS = Viewer.ANONYMOUS;
    private static final Viewer READER = Viewer.of(7, "读者", Set.of("自动确定用户"));
    private static final Viewer EDITOR = Viewer.of(8, "编者", Set.of("用户"));
    private static final Viewer ADMIN = Viewer.of(9, "管理员甲", Set.of("管理员"));

    @Test
    void disabledByDefault() {
        ConfirmEditSettings settings = new ConfirmEditSettings(config(false, null));
        assertFalse(settings.enabled());
        assertFalse(settings.appliesTo(ANONYMOUS), "功能关闭时匿名也不需要验证码");
        assertFalse(settings.appliesTo(EDITOR));
    }

    @Test
    void bypassGroupsSplitByPipeAndNewline() {
        ConfirmEditSettings settings = new ConfirmEditSettings(config(true, "管理员 | 行政员\n自动确定用户|机器人"));
        assertEquals(List.of("管理员", "行政员", "自动确定用户", "机器人"), settings.bypassGroups());

        // 站点设置里没有这个键 = 用默认免验证组（与 core 的默认值一致）
        ConfirmEditSettings defaults = new ConfirmEditSettings(config(true, null));
        assertTrue(defaults.bypassGroups().contains("管理员"));
        assertTrue(defaults.bypassGroups().contains("机器人"));

        // 显式留空 = 无人豁免（与迁移前 core 的行为一致：所有人都要答题）
        assertTrue(new ConfirmEditSettings(config(true, "")).bypassGroups().isEmpty());
    }

    @Test
    void anonymousAlwaysNeedsCaptchaWhileBypassGroupsSkip() {
        ConfirmEditSettings settings = new ConfirmEditSettings(config(true, "管理员|自动确定用户"));
        assertTrue(settings.appliesTo(ANONYMOUS), "匿名用户需要验证码");
        assertTrue(settings.appliesTo(EDITOR), "普通用户需要验证码");
        assertFalse(settings.appliesTo(ADMIN), "免验证组（管理员）跳过");
        assertFalse(settings.appliesTo(READER), "命中任一免验证组即跳过");
    }
}
