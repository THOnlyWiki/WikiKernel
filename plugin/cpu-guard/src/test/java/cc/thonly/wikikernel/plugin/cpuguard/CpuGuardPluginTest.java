package cc.thonly.wikikernel.plugin.cpuguard;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.common.plugin.support.SimplePluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class CpuGuardPluginTest {
    @TempDir Path temp;
    @Test void registersSettingsAndMessagesWithoutTouchingHostLimitsWhenDisabled() {
        var context = SimplePluginContext.create("cpu-guard", temp, Map.of("enabled", "false"));
        var plugin = new CpuGuardPlugin();
        assertEquals("cpu-guard", plugin.descriptor().id());
        plugin.onStart(context);
        try {
            assertEquals(1, context.lookup(SiteSettingsSection.class).size());
            assertEquals(5, context.lookup(SiteSettingsSection.class).getFirst().fields().size());
            assertFalse(plugin.snapshot().options().enabled());
            var messages = context.lookup(MessageBundle.class).getFirst().messages();
            assertEquals(messages.get("zh").keySet(), messages.get("en").keySet());
        } finally { plugin.onStop(context); }
    }
}
