package cc.thonly.wikikernel.plugin.confirmedit;

import cc.thonly.wikikernel.common.extension.EditFormField;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.plugin.support.SimplePluginContext;
import cc.thonly.wikikernel.plugin.confirmedit.hook.ConfirmEditFormField;
import cc.thonly.wikikernel.plugin.confirmedit.hook.ConfirmEditGuard;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfirmEditPlugin}：插件入口注册的扩展点（编辑页字段 + 保存前钩子）、消息包键名
 * 与设置键常量（必须与 core 的站点设置一致）。
 */
class ConfirmEditPluginTest {

    @Test
    void descriptorMatchesThePortedExtension() {
        ConfirmEditPlugin plugin = new ConfirmEditPlugin();
        assertEquals("confirm-edit", plugin.descriptor().id());
        assertEquals("ConfirmEdit", plugin.descriptor().name());
        assertEquals("features.confirm-edit", ConfirmEditPlugin.ENABLED_KEY);
        assertEquals("features.confirm-edit.bypass-groups", ConfirmEditPlugin.BYPASS_GROUPS_KEY);
    }

    @Test
    void onStartRegistersFormFieldGuardAndMessages() {
        SimplePluginContext context = SimplePluginContext.create(
                "confirm-edit", Path.of("build", "test-plugin-data"), Map.of());
        // 单元测试里没有 Spring：siteConfig / messageSource 为 null 时插件仍能装配（开关默认关闭）
        new ConfirmEditPlugin().onStart(context);

        assertEquals(1, context.lookup(EditFormField.class).size());
        assertInstanceOf(ConfirmEditFormField.class, context.lookup(EditFormField.class).get(0));
        assertEquals(1, context.lookup(EditGuard.class).size());
        assertInstanceOf(ConfirmEditGuard.class, context.lookup(EditGuard.class).get(0));

        assertEquals(1, context.lookup(MessageBundle.class).size());
        Map<String, Map<String, String>> messages = context.lookup(MessageBundle.class).get(0).messages();
        // 键名与迁移前 core 的 confirmEdit.* 完全一致
        assertTrue(messages.get("zh").containsKey("confirmEdit.question"), messages.toString());
        assertTrue(messages.get("zh").containsKey("confirmEdit.answerPlaceholder"), messages.toString());
        assertTrue(messages.get("zh").containsKey("confirmEdit.wrong"), messages.toString());
        assertTrue(messages.get("en").containsKey("confirmEdit.wrong"), messages.toString());
    }
}
