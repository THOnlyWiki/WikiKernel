package cc.thonly.wikikernel.i18n;

import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemMessagesDefaultsTest {
    @Test @SuppressWarnings("unchecked")
    void catalogueMatchesCorePrecedenceAndActivePluginFallback() {
        ObjectProvider<PluginManager> managers = mock(ObjectProvider.class);
        ObjectProvider<InterfaceMessageOverrides> overrides = mock(ObjectProvider.class);
        PluginManager manager = mock(PluginManager.class); when(managers.getIfAvailable()).thenReturn(manager);
        MessageBundle bundle = () -> Map.of("en", Map.of("plugin-real-key", "Plugin label", "common.cancel", "wrong precedence"),
                "zh", Map.of("plugin-real-key", "插件文案"));
        when(manager.extensions(MessageBundle.class)).thenReturn(List.of(bundle));
        var source = new PluginMessageSource(managers, overrides);
        assertEquals("Cancel", source.defaults(Locale.ENGLISH).get("common.cancel"));
        assertEquals("Plugin label", source.defaults(Locale.ENGLISH).get("plugin-real-key"));
        assertEquals("插件文案", source.defaults(Locale.SIMPLIFIED_CHINESE).get("plugin-real-key"));
        assertEquals(source.defaults(Locale.ENGLISH).get("plugin-real-key"), source.getMessage("plugin-real-key", null, Locale.ENGLISH));
    }
}
