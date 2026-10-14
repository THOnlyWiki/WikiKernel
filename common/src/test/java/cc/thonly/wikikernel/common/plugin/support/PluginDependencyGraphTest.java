package cc.thonly.wikikernel.common.plugin.support;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PluginDependencyGraph}：拓扑顺序、缺失依赖、循环依赖与稳定顺序。 */
class PluginDependencyGraphTest {

    private static Map<String, List<String>> graph(String... pairs) {
        // 形如 graph("a", "", "b", "a")：b 依赖 a
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            String id = pairs[index];
            String deps = pairs[index + 1];
            dependencies.put(id, deps.isBlank() ? List.of() : List.of(deps.split("\\s*,\\s*")));
        }
        return dependencies;
    }

    @Test
    void dependenciesComeFirst() {
        // 输入顺序故意把依赖者放在前面
        var result = PluginDependencyGraph.sort(graph(
                "c", "b",
                "b", "a",
                "a", ""));

        assertEquals(List.of("a", "b", "c"), result.order());
        assertTrue(result.cyclic().isEmpty());
        assertTrue(result.blocked().isEmpty());
        assertTrue(result.missing().isEmpty());
    }

    @Test
    void independentPluginsKeepDeclarationOrder() {
        var result = PluginDependencyGraph.sort(graph(
                "x", "",
                "y", "",
                "z", ""));

        assertEquals(List.of("x", "y", "z"), result.order());
    }

    @Test
    void missingDependencyIsReportedButNodeStillOrdered() {
        var result = PluginDependencyGraph.sort(graph(
                "a", "",
                "b", "a,ghost"));

        assertEquals(List.of("a", "b"), result.order());
        assertEquals(List.of("ghost"), result.missing().get("b"));
        assertNull(result.missing().get("a"));
    }

    @Test
    void cycleMembersAndTheirDependentsAreUnresolved() {
        var result = PluginDependencyGraph.sort(graph(
                "a", "b",
                "b", "a",
                "c", "a",
                "d", ""));

        assertEquals(List.of("d"), result.order());
        assertEquals(List.of("a", "b"), result.cyclic());
        assertEquals(List.of("c"), result.blocked());
        assertTrue(result.unresolvedReason("a").contains("循环依赖"));
        assertTrue(result.unresolvedReason("b").contains("循环依赖"));
        assertTrue(result.unresolvedReason("c").contains("循环依赖中"));
        assertNull(result.unresolvedReason("d"));
        assertEquals(List.of("a", "b", "a"), result.cyclePaths().get("a"));
        assertEquals(List.of("b", "a", "b"), result.cyclePaths().get("b"));
    }

    @Test
    void selfDependencyIsACycle() {
        var result = PluginDependencyGraph.sort(graph("a", "a", "b", ""));

        assertEquals(List.of("b"), result.order());
        assertEquals(List.of("a"), result.cyclic());
        assertEquals(List.of("a", "a"), result.cyclePaths().get("a"));
    }

    @Test
    void orderWithBrokenCyclesKeepsEveryPlugin() {
        var result = PluginDependencyGraph.sort(graph(
                "a", "b",
                "b", "a",
                "c", "a",
                "d", ""));

        assertEquals(List.of("d", "a", "b", "c"), result.orderWithBrokenCycles());
    }

    @Test
    void threeNodeCycleIsDetected() {
        var result = PluginDependencyGraph.sort(graph(
                "a", "b",
                "b", "c",
                "c", "a"));

        assertTrue(result.order().isEmpty());
        assertEquals(List.of("a", "b", "c"), result.cyclic());
        assertNotNull(result.cyclePaths().get("b"));
        assertEquals(4, result.cyclePaths().get("b").size());
    }
}
