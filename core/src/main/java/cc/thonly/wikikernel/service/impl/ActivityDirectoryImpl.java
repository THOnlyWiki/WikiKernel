package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.extension.ActivityProvider;
import cc.thonly.wikikernel.common.service.ActivityDirectory;
import cc.thonly.wikikernel.plugin.PluginManager;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ActivityDirectoryImpl implements ActivityDirectory {
    private final PluginManager plugins;
    public ActivityDirectoryImpl(PluginManager plugins) { this.plugins = plugins; }
    public List<ActivityProvider.Activity> activities(int year) {
        return plugins.extensions(ActivityProvider.class).stream().flatMap(p -> p.activities(year).stream()).toList();
    }
    public List<String> regions() {
        return plugins.extensions(ActivityProvider.class).stream().flatMap(p -> p.regions().stream()).distinct().toList();
    }
}
