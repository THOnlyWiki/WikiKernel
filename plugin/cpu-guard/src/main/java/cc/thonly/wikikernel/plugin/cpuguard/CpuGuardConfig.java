package cc.thonly.wikikernel.plugin.cpuguard;

import cc.thonly.wikikernel.common.plugin.PluginConfig;
import cc.thonly.wikikernel.common.service.SiteConfig;

/** 站点设置优先，插件 YAML / 启动器参数兜底；任何非法设置都回落到保守默认值。 */
final class CpuGuardConfig {
    static final String PREFIX = "cpu-guard.";
    static final String ENABLED = PREFIX + "enabled";
    static final String PERCENT = PREFIX + "percent";
    static final String BASIS = PREFIX + "basis";
    static final String CORES = PREFIX + "locked-cores";
    static final String MACHINE_CPUS = PREFIX + "machine-cpus";
    static final long PERIOD_US = 100_000L;

    record Options(boolean enabled, int percent, String basis, int lockedCores, int machineCpus) {
        long quotaMicros() {
            int denominator = "single-core".equals(basis) ? 1 : machineCpus;
            return PERIOD_US * denominator * percent / 100L;
        }
        double machinePercent(long quota, long period) {
            return 100.0 * quota / period / machineCpus;
        }
    }

    private final PluginConfig yaml;
    private final SiteConfig site;
    private final int detectedCpus;

    CpuGuardConfig(PluginConfig yaml, SiteConfig site, int detectedCpus) {
        this.yaml = yaml;
        this.site = site;
        this.detectedCpus = Math.max(1, Math.min(4096, detectedCpus));
    }

    String defaultValue(String suffix, String fallback) {
        String property = System.getProperty("wikikernel.cpu-guard." + suffix, fallback);
        return yaml == null ? property : yaml.get(suffix, property);
    }

    private String value(String suffix, String fallback) {
        String base = defaultValue(suffix, fallback);
        String stored = site == null ? "" : site.get(PREFIX + suffix, "");
        return stored == null || stored.isBlank() ? base : stored.trim();
    }

    Options read() {
        String enabled = value("enabled", "true");
        boolean on = !java.util.Set.of("false", "0", "off", "no")
                .contains(enabled.trim().toLowerCase(java.util.Locale.ROOT));
        int percent = integer(value("percent", "25"), 1, 30, 25);
        String basis = value("basis", "single-core");
        if (!"machine".equals(basis)) basis = "single-core";
        int cores = integer(value("locked-cores", "1"), 0, 4096, 1);
        int machine = integer(value("machine-cpus", "2"), 0, 4096, 0);
        machine = machine == 0 ? detectedCpus : Math.min(machine, detectedCpus);
        return new Options(on, percent, basis, cores, machine);
    }

    private static int integer(String raw, int min, int max, int fallback) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= min && value <= max ? value : fallback;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
