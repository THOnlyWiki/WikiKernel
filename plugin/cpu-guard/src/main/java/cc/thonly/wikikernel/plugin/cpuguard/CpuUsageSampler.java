package cc.thonly.wikikernel.plugin.cpuguard;

import java.util.ArrayDeque;

/** 用 JVM 进程 CPU 时间增量计量，避免 ActiveProcessorCount 改变百分比的分母。 */
final class CpuUsageSampler {
    private record Sample(long wall, long cpu) {}
    record Usage(boolean available, double machinePercent, double singleCorePercent,
                 double averageMachinePercent, double seconds) {}
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long previousWall = -1, previousCpu = -1, totalWall, totalCpu;
    private int previousProcessors;

    synchronized Usage sample(long wall, long cpu, int machineCpus) {
        if (cpu < 0 || machineCpus < 1) return new Usage(false, 0, 0, 0, 0);
        if (previousWall < 0 || wall <= previousWall || cpu < previousCpu || previousProcessors != machineCpus) {
            previousWall = wall;
            previousCpu = cpu;
            previousProcessors = machineCpus;
            samples.clear();
            totalWall = totalCpu = 0;
            return new Usage(false, 0, 0, 0, 0);
        }
        long elapsed = wall - previousWall, consumed = cpu - previousCpu;
        previousWall = wall;
        previousCpu = cpu;
        samples.addLast(new Sample(elapsed, consumed));
        totalWall += elapsed;
        totalCpu += consumed;
        while (samples.size() > 1 && totalWall - samples.peekFirst().wall() >= 60_000_000_000L) {
            Sample removed = samples.removeFirst();
            totalWall -= removed.wall();
            totalCpu -= removed.cpu();
        }
        double oneCore = 100.0 * consumed / elapsed;
        return new Usage(true, oneCore / machineCpus, oneCore,
                100.0 * totalCpu / totalWall / machineCpus, totalWall / 1_000_000_000.0);
    }
}
