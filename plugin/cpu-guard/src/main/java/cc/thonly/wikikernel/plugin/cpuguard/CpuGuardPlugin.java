package cc.thonly.wikikernel.plugin.cpuguard;

import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Debian / Linux cgroup v2 锁核与 JVM 整进程 CPU 配额；权限不足时明确报告未生效。 */
public final class CpuGuardPlugin implements WikiPlugin {
    @Autowired(required = false) private SiteConfig siteConfig;
    @Autowired(required = false) private MessageSource messageSource;
    private ScheduledExecutorService worker;
    private LinuxCgroupLimiter limiter;
    private CpuGuardConfig config;
    private final CpuUsageSampler sampler = new CpuUsageSampler();
    private volatile Snapshot snapshot;
    private String lastState = "";
    private boolean warnedHigh;

    record Snapshot(CpuGuardConfig.Options options, LinuxCgroupLimiter.Result result,
                    String error, CpuUsageSampler.Usage usage) {}

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("cpu-guard", "CPU Guard", "1.0.0",
                "Debian cgroup v2 CPU quota and core affinity for the JVM");
    }

    @Override
    public synchronized void onStart(PluginContext context) {
        config = new CpuGuardConfig(context.config(), siteConfig, detectProcessors());
        context.register(MessageBundle.class, new CpuGuardMessages());
        context.register(SiteSettingsSection.class, new CpuGuardSettings(this, messageSource, config));
        tick(context);
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "wikikernel-cpu-guard");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(() -> tick(context), 5, 5, TimeUnit.SECONDS);
    }

    private void tick(PluginContext context) {
        try {
            CpuGuardConfig.Options options = config.read();
            LinuxCgroupLimiter.Result result = null;
            String error = "";
            try {
                if (!options.enabled()) {
                    if (limiter != null) limiter.release();
                } else {
                    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
                        throw new IOException("CPU Guard requires Linux cgroup v2; this platform is monitoring only");
                    }
                    if (limiter == null) {
                        Path group = LinuxCgroupLimiter.discover(Path.of("/proc/self/cgroup"), Path.of("/proc/self/mountinfo"));
                        limiter = new LinuxCgroupLimiter(group, ProcessHandle.current().pid(), CpuGuardPlugin::isOwnedProcess);
                    }
                    result = limiter.apply(options);
                }
            } catch (IOException | RuntimeException exception) {
                error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            }
            long processCpu = ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean
                    ? bean.getProcessCpuTime() : -1;
            CpuUsageSampler.Usage usage = sampler.sample(System.nanoTime(), processCpu, options.machineCpus());
            snapshot = new Snapshot(options, result, error, usage);
            String state = options.enabled() + ":" + options.percent() + ":" + options.basis() + ":"
                    + (result == null ? error : result.quotaActive() + ":" + result.affinityActive() + ":" + result.detail());
            if (!state.equals(lastState)) {
                boolean active = result != null && result.quotaActive();
                context.logger().log(active || !options.enabled() ? System.Logger.Level.INFO : System.Logger.Level.WARNING,
                        "CPU Guard: {0}", summary());
                lastState = state;
            }
            double average = usage.averageMachinePercent() * ("single-core".equals(options.basis()) ? options.machineCpus() : 1);
            boolean high = options.enabled() && usage.available() && usage.seconds() >= 60 && average > 30.0;
            if (high && !warnedHigh) {
                context.logger().log(System.Logger.Level.WARNING,
                        "CPU Guard: recent JVM CPU usage exceeded 30%; verify quota, vCPU count and provider accounting: {0}", summary());
            }
            warnedHigh = high;
        } catch (RuntimeException exception) {
            // 配置或指标读取失败不能终止后续监测；也不把失败伪装成已限额。
            snapshot = new Snapshot(null, null, exception.toString(), new CpuUsageSampler.Usage(false, 0, 0, 0, 0));
            String failure = exception.toString();
            if (!failure.equals(lastState)) {
                context.logger().log(System.Logger.Level.WARNING, "CPU Guard monitor failed", exception);
                lastState = failure;
            }
        }
    }

    Snapshot snapshot() { return snapshot; }

    String summary() {
        Snapshot current = snapshot;
        if (current == null) return "initializing";
        if (!current.error().isEmpty()) return "NOT ENFORCED: " + current.error();
        if (!current.options().enabled()) return "disabled (external systemd limits remain in force)";
        var result = current.result();
        return (result.quotaActive() ? "CPU quota active" : "CPU quota NOT active")
                + ", affinity=" + result.affinityActive() + ", CPUs=" + result.cpus()
                + ", cpu.max=" + result.quota() + " " + result.period() + "; " + result.detail();
    }

    private static boolean isOwnedProcess(long pid) {
        var process = ProcessHandle.of(pid);
        if (process.isEmpty()) return true; // 子进程可能恰好退出，不能把已消失的 PID 当作共享进程。
        for (var parent = process.get().parent(); parent.isPresent(); parent = parent.get().parent()) {
            if (parent.get().pid() == ProcessHandle.current().pid()) return true;
        }
        return false;
    }

    static int detectProcessors() {
        try {
            int online = CpuSet.parse(Files.readString(Path.of("/sys/devices/system/cpu/online"))).size();
            if (online > 0) return online;
        } catch (IOException | RuntimeException ignored) {}
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    @Override
    public synchronized void onStop(PluginContext context) {
        if (worker != null) {
            worker.shutdownNow();
            try { worker.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            worker = null;
        }
        if (limiter != null) {
            try { limiter.close(); }
            catch (IOException exception) {
                context.logger().log(System.Logger.Level.WARNING, "CPU Guard could not restore its own settings", exception);
            }
            limiter = null;
        }
    }
}
