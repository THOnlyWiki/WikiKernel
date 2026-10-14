package cc.thonly.wikikernel.common.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PluginDescriptor}：四参兼容构造 + 依赖声明归一化 + core 版本约束。 */
class PluginDescriptorTest {

    @Test
    void fourArgConstructorStaysBackwardCompatible() {
        PluginDescriptor descriptor = new PluginDescriptor("comments", "Comments", "1.0.0", "评论区");

        assertEquals("comments", descriptor.id());
        assertEquals("Comments", descriptor.name());
        assertEquals("1.0.0", descriptor.version());
        assertEquals("评论区", descriptor.description());
        assertTrue(descriptor.requires().isEmpty());
        assertTrue(descriptor.clientRequires().isEmpty());
        assertFalse(descriptor.hasRequires());
        assertFalse(descriptor.hasClientRequires());
        assertTrue(descriptor.coreConstraint().isAny());
        assertEquals("", descriptor.requiresCore());
    }

    @Test
    void defaultsForMissingMetadata() {
        PluginDescriptor descriptor = PluginDescriptor.of("no-title", "0.4.0");

        assertEquals("no-title", descriptor.name());
        assertEquals("", descriptor.description());
    }

    @Test
    void blankIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PluginDescriptor(" ", "x", "1", ""));
        assertThrows(NullPointerException.class, () -> new PluginDescriptor(null, "x", "1", ""));
    }

    @Test
    void dependencyListsAreTrimmedDeduplicatedAndImmutable() {
        PluginDescriptor descriptor = new PluginDescriptor("a", "A", "1.0", "",
                List.of(" b ", "b", "", "  ", "c"), " >=0.0.1 ",
                List.of("c", "c", "d"));

        assertEquals(List.of("b", "c"), descriptor.requires());
        assertEquals(List.of("c", "d"), descriptor.clientRequires());
        assertEquals(">=0.0.1", descriptor.requiresCore());
        assertFalse(descriptor.coreConstraint().isAny());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.requires().add("x"));
    }

    @Test
    void nullDependencyListsMeanNoDependency() {
        PluginDescriptor descriptor = new PluginDescriptor("a", "A", "1.0", "", null, null, null);

        assertTrue(descriptor.requires().isEmpty());
        assertTrue(descriptor.clientRequires().isEmpty());
        assertEquals("", descriptor.requiresCore());
        assertTrue(descriptor.coreConstraint().isAny());
    }
}
