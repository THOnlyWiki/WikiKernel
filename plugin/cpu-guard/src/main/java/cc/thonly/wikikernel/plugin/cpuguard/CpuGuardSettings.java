package cc.thonly.wikikernel.plugin.cpuguard;

import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;

final class CpuGuardSettings implements SiteSettingsSection {
    private final CpuGuardPlugin plugin;
    private final MessageSource messages;
    private final CpuGuardConfig config;

    CpuGuardSettings(CpuGuardPlugin plugin, MessageSource messages, CpuGuardConfig config) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
    }

    private String text(String suffix, String fallback) {
        if (messages == null) return fallback;
        return messages.getMessage("cpuGuard." + suffix, null, fallback, LocaleContextHolder.getLocale());
    }

    @Override public String id() { return "cpu-guard"; }
    @Override public int order() { return 160; }
    @Override public String title() { return text("title", "CPU 限额与锁核"); }

    @Override
    public String description() {
        String prefix = text("description", "默认目标 25%，5 秒内应用设置；machine=整台 VPS，single-core=单核。必须使用独占 cgroup v2，建议通过附带 systemd 启动器运行。");
        CpuGuardPlugin.Snapshot snapshot = plugin.snapshot();
        if (snapshot == null) return prefix;
        String status = !snapshot.error().isEmpty() ? text("notEnforced", "限制未生效") + ": " + snapshot.error()
                : !snapshot.options().enabled() ? text("disabled", "已关闭（外部 systemd 上限仍然生效）")
                : text(snapshot.result().quotaActive() ? "quotaActive" : "notEnforced", "CPU quota")
                        + "; " + text("affinity", "核心绑定") + ": " + (snapshot.options().lockedCores() == 0
                                ? text("affinityDisabled", "未启用") : snapshot.result().affinityActive())
                        + "; CPUs=" + snapshot.result().cpus()
                        + "; " + text("actualCap", "实际整机配额") + ": "
                        + (snapshot.result().quota() < 0 ? "unlimited" : String.format(Locale.ROOT, "%.2f%%",
                                snapshot.options().machinePercent(snapshot.result().quota(), snapshot.result().period())))
                        + "; " + snapshot.result().detail();
        if (snapshot.usage().available()) {
            status += String.format(Locale.ROOT, "; CPU %.2f%% (machine), %.2f%% (single-core), recent %.2f%% (machine, %.0fs)",
                    snapshot.usage().machinePercent(), snapshot.usage().singleCorePercent(),
                    snapshot.usage().averageMachinePercent(), snapshot.usage().seconds());
        }
        return prefix + " " + status;
    }

    @Override
    public List<SettingField> fields() {
        return List.of(
                SettingField.checkbox(CpuGuardConfig.ENABLED, text("enabled", "启用系统级 CPU 配额"),
                        Boolean.parseBoolean(config.defaultValue("enabled", "true"))),
                SettingField.text(CpuGuardConfig.PERCENT, text("percent", "CPU 百分比（1–30，默认 25）"), config.defaultValue("percent", "25")),
                SettingField.select(CpuGuardConfig.BASIS, text("basis", "百分比口径（machine=整机；single-core=单核）"),
                        config.defaultValue("basis", "single-core"), List.of("machine", "single-core")),
                SettingField.text(CpuGuardConfig.CORES, text("cores", "绑定的核心数（默认 1；0=仅限额）"), config.defaultValue("locked-cores", "1")),
                SettingField.text(CpuGuardConfig.MACHINE_CPUS, text("machineCpus", "VPS vCPU 总数（0=自动；容器中请填写 VPS 实际值）"),
                        config.defaultValue("machine-cpus", "2")));
    }
}

class CpuGuardMessages extends ResourceMessageBundle {}
