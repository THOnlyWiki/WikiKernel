package cc.thonly.wikikernel.plugin.cpuguard;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * 仅控制当前 JVM 独占的 cgroup v2 叶子，不移动进程、不修改 SSH 会话 / slice / 根 cgroup。
 * CPU 配额覆盖本 JVM 的所有普通调度线程（包括 GC / JIT）；保留启动器或管理员更严格的上限。
 */
final class LinuxCgroupLimiter implements AutoCloseable {
    record Result(boolean quotaActive, boolean affinityActive, long quota, long period, String cpus, String detail) {}
    private record Quota(long amount, long period) {
        static Quota parse(String raw) {
            String[] parts = raw.trim().split("\\s+");
            if (parts.length != 2) throw new IllegalArgumentException("Invalid cpu.max");
            long amount = "max".equals(parts[0]) ? -1 : Long.parseLong(parts[0]);
            long period = Long.parseLong(parts[1]);
            if (period <= 0 || (amount != -1 && amount <= 0)) throw new IllegalArgumentException("Invalid CPU quota");
            return new Quota(amount, period);
        }
        long atPeriod(long targetPeriod) {
            if (amount < 0) return Long.MAX_VALUE;
            return BigInteger.valueOf(amount).multiply(BigInteger.valueOf(targetPeriod))
                    .divide(BigInteger.valueOf(period)).min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
        }
    }

    @FunctionalInterface
    interface Writer { void write(Path path, String value) throws IOException; }

    private final Writer writer;
    private final Path group;
    private final long pid;
    private final LongPredicate ownedProcess;
    private String originalQuota, originalCpus, lastQuota, lastCpus;
    private List<Integer> availableCpus;
    private boolean closed;

    LinuxCgroupLimiter(Path group, long pid, LongPredicate ownedProcess) {
        this(group, pid, ownedProcess, (path, value) -> Files.writeString(path, value + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
    }

    LinuxCgroupLimiter(Path group, long pid, LongPredicate ownedProcess, Writer writer) {
        this.writer = writer;
        this.group = group;
        this.pid = pid;
        this.ownedProcess = ownedProcess;
    }

    static Path discover(Path proc, Path mountInfo) throws IOException {
        String path = Files.readAllLines(proc, StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("0::")).map(line -> line.substring(3)).findFirst()
                .orElseThrow(() -> new IOException("cgroup v2 is unavailable"));
        if (!path.startsWith("/") || path.contains("..")) throw new IOException("Invalid cgroup membership path");
        for (String line : Files.readAllLines(mountInfo, StandardCharsets.UTF_8)) {
            String[] halves = line.split(" - ", 2);
            if (halves.length != 2 || !halves[1].startsWith("cgroup2 ")) continue;
            String[] fields = halves[0].split(" ");
            if (fields.length < 5) continue;
            Path root = Path.of(unescape(fields[3])).normalize();
            Path member = Path.of(path).normalize();
            if (!member.startsWith(root)) continue;
            Path mount = Path.of(unescape(fields[4])).toRealPath();
            Path result = mount.resolve(root.relativize(member)).normalize().toRealPath();
            if (result.startsWith(mount) && !result.equals(mount)) return result;
        }
        throw new IOException("A dedicated non-root cgroup v2 is required");
    }

    private static String unescape(String value) {
        return value.replace("\\040", " ").replace("\\011", "\t")
                .replace("\\012", "\n").replace("\\134", "\\");
    }

    synchronized Result apply(CpuGuardConfig.Options options) throws IOException {
        if (closed) throw new IOException("Limiter is closed");
        verifyExclusive();
        String currentQuota = read("cpu.max");
        if (originalQuota == null || (lastQuota != null && !lastQuota.equals(currentQuota))) {
            originalQuota = currentQuota;
        }
        long amount = Math.min(options.quotaMicros(), Quota.parse(originalQuota).atPeriod(CpuGuardConfig.PERIOD_US));
        String desired = amount + " " + CpuGuardConfig.PERIOD_US;
        String detail = "";
        if (Quota.parse(currentQuota).atPeriod(CpuGuardConfig.PERIOD_US) != amount) {
            try {
                write("cpu.max", desired);
                lastQuota = desired;
            } catch (IOException exception) {
                detail = "CPU quota is not writable; configure CPUQuota in systemd";
            }
        }
        Quota actual = Quota.parse(read("cpu.max"));
        boolean quotaActive = actual.amount() > 0
                && actual.atPeriod(CpuGuardConfig.PERIOD_US) <= amount;
        if (!quotaActive && detail.isEmpty()) detail = "Requested CPU quota did not take effect";

        String selected = "";
        boolean affinity = false;
        if (Files.isRegularFile(group.resolve("cpuset.cpus"))
                && Files.isRegularFile(group.resolve("cpuset.cpus.effective"))) {
            String currentCpus = read("cpuset.cpus");
            if (originalCpus == null || (lastCpus != null && !lastCpus.equals(currentCpus))) {
                originalCpus = currentCpus;
                availableCpus = CpuSet.parse(read("cpuset.cpus.effective"));
            }
            if (options.lockedCores() == 0) {
                restoreFile("cpuset.cpus", lastCpus, originalCpus);
                lastCpus = null;
                selected = read("cpuset.cpus.effective");
            } else if (!availableCpus.isEmpty()) {
                selected = CpuSet.first(availableCpus, options.lockedCores());
                if (!CpuSet.parse(read("cpuset.cpus.effective")).equals(CpuSet.parse(selected))) {
                    try {
                        write("cpuset.cpus", selected);
                        lastCpus = selected;
                    } catch (IOException exception) {
                        detail = append(detail, "CPU affinity is not writable; configure AllowedCPUs in systemd");
                    }
                }
                List<Integer> effective = CpuSet.parse(read("cpuset.cpus.effective"));
                List<Integer> requested = CpuSet.parse(selected);
                affinity = !effective.isEmpty() && requested.containsAll(effective);
                selected = read("cpuset.cpus.effective");
            }
        } else if (options.lockedCores() > 0) {
            detail = append(detail, "cpuset controller is unavailable; CPU quota remains independent of affinity");
        }
        if (options.lockedCores() > 0 && !affinity && detail.isEmpty()) detail = "Requested CPU affinity did not take effect";
        return new Result(quotaActive, affinity, actual.amount(), actual.period(), selected, detail);
    }

    private void verifyExclusive() throws IOException {
        if (!Files.isRegularFile(group.resolve("cpu.max"))) throw new IOException("cpu.max is unavailable");
        boolean containsJvm = false;
        for (String line : Files.readAllLines(group.resolve("cgroup.procs"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            long member;
            try { member = Long.parseLong(line.trim()); }
            catch (NumberFormatException exception) { throw new IOException("Invalid cgroup membership", exception); }
            containsJvm |= member == pid;
            if (member != pid && !ownedProcess.test(member)) {
                throw new IOException("Refusing a shared cgroup; start the JVM using the supplied systemd launcher");
            }
        }
        if (!containsJvm) throw new IOException("JVM is no longer in the controlled cgroup");
        try (var children = Files.list(group)) {
            if (children.anyMatch(Files::isDirectory)) throw new IOException("A leaf cgroup is required");
        }
    }

    private String read(String file) throws IOException {
        return Files.readString(group.resolve(file), StandardCharsets.UTF_8).trim().replaceAll("\\s+", " ");
    }

    private void write(String file, String value) throws IOException {
        writer.write(group.resolve(file), value);
    }

    private static String append(String existing, String text) {
        return existing.isEmpty() ? text : existing + "; " + text;
    }

    private void restoreFile(String file, String last, String original) throws IOException {
        if (last != null && original != null && read(file).equals(last)) write(file, original);
    }

    synchronized void release() throws IOException {
        if (originalQuota == null && originalCpus == null) return;
        verifyExclusive();
        IOException failure = null;
        try { restoreFile("cpu.max", lastQuota, originalQuota); }
        catch (IOException exception) { failure = exception; }
        try { restoreFile("cpuset.cpus", lastCpus, originalCpus); }
        catch (IOException exception) { failure = exception; }
        if (failure != null) throw failure;
        lastQuota = lastCpus = null;
    }

    @Override
    public synchronized void close() throws IOException {
        try { release(); } finally { closed = true; }
    }
}
