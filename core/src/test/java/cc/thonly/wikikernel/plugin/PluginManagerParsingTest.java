package cc.thonly.wikikernel.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 描述文件里逗号分隔的插件 id 列表（{@code plugin.requires} / {@code plugin.clientRequires}）解析。 */
class PluginManagerParsingTest {

    @Test
    void blankValuesMeanNoDependency() {
        assertTrue(PluginManager.parseIds(null).isEmpty());
        assertTrue(PluginManager.parseIds("").isEmpty());
        assertTrue(PluginManager.parseIds("   ").isEmpty());
    }

    @Test
    void idsAreTrimmedDeduplicatedAndKeepDeclarationOrder() {
        assertEquals(List.of("b", "a", "c"), PluginManager.parseIds(" b , a ,b,, c "));
    }

    @Test
    void singleIdWorks() {
        assertEquals(List.of("comments"), PluginManager.parseIds("comments"));
    }
}
