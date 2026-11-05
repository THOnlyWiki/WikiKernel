package cc.thonly.wikikernel.plugin.cpuguard;
import cc.thonly.wikikernel.common.plugin.support.MapPluginConfig;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class CpuGuardConfigTest {
    private CpuGuardConfig config(Map<String, String> yaml, Map<String, String> site, int cpus) {
        return new CpuGuardConfig(new MapPluginConfig(yaml), new SiteConfig() {
            public String get(String key, String fallback) { return site.getOrDefault(key, fallback); }
            public boolean getBoolean(String key, boolean fallback) { return Boolean.parseBoolean(get(key, "" + fallback)); }
        }, cpus);
    }
    @Test void conservativeDefaultIs25PercentOfOneCpuOnAnyVps() {
        for (int cpus : new int[]{1, 2, 4, 8, 64}) {
            var value = config(Map.of(), Map.of(), cpus).read();
            assertTrue(value.enabled());
            assertEquals("single-core", value.basis());
            assertEquals(25_000L, value.quotaMicros());
            assertEquals(1, value.lockedCores());
            assertEquals(Math.min(2, cpus), value.machineCpus());
            assertTrue(value.machinePercent(value.quotaMicros(), 100_000) <= 25);
        }
    }
    @Test void wholeMachinePercentAndExplicitVcpuCountHaveCorrectUnits() {
        var value = config(Map.of("basis", "machine", "machine-cpus", "0"), Map.of(), 4).read();
        assertEquals(100_000L, value.quotaMicros());
        assertEquals(25.0, value.machinePercent(100_000, 100_000));
        value = config(Map.of("percent", "20", "basis", "machine"),
                Map.of("cpu-guard.percent", "28", "cpu-guard.machine-cpus", "2"), 4).read();
        assertEquals(56_000L, value.quotaMicros());
    }
    @Test void invalidSettingsCannotRaiseQuotaAbove30() {
        for (String bad : new String[]{"31", "100", "-1", "0", "not-a-number"}) {
            assertEquals(25, config(Map.of(), Map.of("cpu-guard.percent", bad), 4).read().percent());
        }
        assertEquals("single-core", config(Map.of("basis", "bad"), Map.of(), 4).read().basis());
        assertEquals(4, config(Map.of("machine-cpus", "64"), Map.of(), 4).read().machineCpus());
        assertFalse(config(Map.of("enabled", "false"), Map.of(), 4).read().enabled());
        assertEquals(0, config(Map.of("locked-cores", "0"), Map.of(), 4).read().lockedCores());
    }
    @Test void cpuListsAreNormalizedAndBounded() {
        assertEquals(java.util.List.of(0, 1, 2, 3, 7), CpuSet.parse("0-3,7,1"));
        assertEquals("0,1", CpuSet.first(CpuSet.parse("0-3,7"), 2));
        for (String bad : new String[]{"3-1", "0-999999", "1;id", "0,,1", "-1", "0-a"}) {
            assertThrows(IllegalArgumentException.class, () -> CpuSet.parse(bad));
        }
    }
}
