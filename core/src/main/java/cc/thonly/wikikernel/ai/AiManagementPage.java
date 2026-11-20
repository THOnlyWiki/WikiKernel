package cc.thonly.wikikernel.ai;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.web.WikiUrls;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class AiManagementPage implements SpecialPage {
    private final cc.thonly.wikikernel.common.service.CurrentUser current;
    public AiManagementPage(cc.thonly.wikikernel.common.service.CurrentUser current) { this.current = current; }
    public String name() { return "AI管理"; }
    public String displayName() { return "AI 管理"; }
    public List<String> aliases() { return List.of("AIManagement"); }
    public String template() { return "special/ai-management"; }
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        model.put("aiManager", AiManagementSupport.isManager(current.viewer()));
        model.put("aiAgentUrl", WikiUrls.pagePath("特殊:AI代理"));
    }
}
