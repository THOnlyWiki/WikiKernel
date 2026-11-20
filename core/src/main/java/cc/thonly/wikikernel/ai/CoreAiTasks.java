package cc.thonly.wikikernel.ai;

import cc.thonly.wikikernel.common.ai.*;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.LogWriter;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Arrays;
import java.util.Set;

@Service
public class CoreAiTasks implements AiTasks {
    public static final Set<String> SCENES = Set.of("activity", "news");
    private final AiProviderRegistry registry;
    private final SiteConfig settings;
    private final AccessCheck access;
    private final LogWriter logs;

    public CoreAiTasks(AiProviderRegistry registry, SiteConfig settings, AccessCheck access, LogWriter logs) {
        this.registry = registry; this.settings = settings; this.access = access; this.logs = logs;
    }

    private AiProviderRegistry.Selection selection(String scene) {
        if (!SCENES.contains(scene) || !settings.getBoolean("ai.scene." + scene + ".enabled", false)) {
            throw new IllegalArgumentException("请先在 AI 管理中启用此场景。");
        }
        String id = settings.get("ai.scene." + scene + ".provider", "");
        return registry.selection(0, id).orElseThrow(() -> new IllegalArgumentException("此场景没有可用的站点模型。"));
    }

    @Override
    public boolean configured(String scene) {
        try { selection(scene); return true; }
        catch (IllegalArgumentException exception) { return false; }
    }

    @Override
    public String model(String scene) { return selection(scene).model(); }

    @Override
    public boolean allowed(String scene, Viewer actor) {
        if (!SCENES.contains(scene) || actor == null || !actor.loggedIn() || access.blockNotice(actor).isPresent()) return false;
        String groups = settings.get("ai.scene." + scene + ".groups", "管理员|行政员");
        return Arrays.stream(groups.split("\\|")).anyMatch(actor::inGroup);
    }

    @Override
    public String complete(String scene, Viewer actor, String system, String input) {
        if (!allowed(scene, actor)) {
            throw new IllegalArgumentException("你没有使用这个 AI 场景的权限。");
        }
        if (input == null || input.length() > 120000) throw new IllegalArgumentException("输入内容过长。");
        var selected = selection(scene);
        long started = System.nanoTime();
        try {
            return selected.provider().complete(List.of(AiMessage.system(system), AiMessage.user(input)));
        } finally {
            logs.record("ai", actor.id(), "特殊:AI管理", scene + " / " + selected.vendorId() + " / "
                    + selected.model() + " / " + ((System.nanoTime() - started) / 1000000) + "ms");
        }
    }
}
