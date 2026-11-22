package cc.thonly.wikikernel.plugin.disambiguator;

import cc.thonly.wikikernel.common.extension.BehaviorSwitch;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.PageReportStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

import java.util.List;

public class DisambiguatorPlugin implements WikiPlugin {
    static final String PROPERTY = "disambiguation";

    @Autowired
    private PageReportStore reports;
    @Autowired
    private AccessCheck access;
    @Autowired
    private MessageSource messages;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("disambiguator", "Disambiguator", "1.0.0",
                "Disambiguation pages and links to them");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(MessageBundle.class, new Messages());
        context.register(BehaviorSwitch.class, new DisambiguationSwitch());
        context.register(SpecialPage.class, new DisambiguationReport(false, reports, access, messages));
        context.register(SpecialPage.class, new DisambiguationReport(true, reports, access, messages));
    }

    private static final class Messages extends ResourceMessageBundle {}

    private static final class DisambiguationSwitch implements BehaviorSwitch {
        @Override
        public String name() {
            return "DISAMBIG";
        }

        @Override
        public List<String> aliases() {
            return List.of("消歧义", "消除歧义", "消歧義", "消除歧義", "曖昧さ回避");
        }

        @Override
        public void apply(WikiRenderContext context) {
            context.setProperty(PROPERTY, "");
        }
    }
}
