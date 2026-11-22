package cc.thonly.wikikernel.plugin.twocolconflict;

import cc.thonly.wikikernel.common.extension.EditConflictUi;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;

public final class TwoColConflictPlugin implements WikiPlugin {
    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("two-col-conflict", "TwoColConflict", "1.0.0",
                "Paragraph-based edit conflict resolution");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new Messages());
        if (context.config().getBoolean("enabled", true)) {
            context.register(EditConflictUi.class, () -> "wiki/two-col-conflict");
        }
    }

    private static final class Messages extends ResourceMessageBundle {}
}
