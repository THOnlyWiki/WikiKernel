package cc.thonly.wikikernel.plugin.cpuguard;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CpuUsageSamplerTest {
    @Test void normalizesAgainstMachineCpusAndWeightsHistoryByElapsedTime() {
        var sampler = new CpuUsageSampler();
        assertFalse(sampler.sample(0, 0, 4).available());
        var usage = sampler.sample(1_000_000_000L, 1_000_000_000L, 4);
        assertEquals(100, usage.singleCorePercent(), 0.001);
        assertEquals(25, usage.machinePercent(), 0.001);
        usage = sampler.sample(5_000_000_000L, 1_000_000_000L, 4);
        assertEquals(0, usage.machinePercent(), 0.001);
        assertEquals(5, usage.averageMachinePercent(), 0.001);
        assertFalse(sampler.sample(6_000_000_000L, 1_000_000_000L, 2).available());
    }
    @Test void unavailableCounterIsNotReportedAsZeroCpuUsage() {
        assertFalse(new CpuUsageSampler().sample(1000, -1, 4).available());
    }
}
