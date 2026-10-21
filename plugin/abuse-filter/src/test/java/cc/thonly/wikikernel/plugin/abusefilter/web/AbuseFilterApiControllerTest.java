package cc.thonly.wikikernel.plugin.abusefilter.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterFakes;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterSupport;
import cc.thonly.wikikernel.plugin.abusefilter.PluginContextFake;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 预览 API：$1 参数替换与 WikiKernel: 消息页命名空间。 */
class AbuseFilterApiControllerTest {

    @Test
    void previewExpandsDescriptionAndUsesWikiKernelNamespace() {
        AbuseFilterFakes.Fixture fixture = new AbuseFilterFakes.Fixture();
        fixture.addUser("管理员甲", AbuseFilterSupport.GROUP_ADMIN);
        fixture.addPage("WikiKernel:Abusefilter-warning", "警告：$1");

        AbuseFilterApiController controller = new AbuseFilterApiController(
                fixture.service(), fixture.support(),
                () -> Viewer.of(1L, "管理员甲", Set.of(AbuseFilterSupport.GROUP_ADMIN)));
        PluginContextFake.inject(controller, "pageStore", fixture.pageStore());

        AjaxResult<Map<String, Object>> result = controller.messagePreview(
                "", "Abusefilter-warning", "禁止广告");

        assertTrue(result.isSuccess(), result.getMessage());
        assertEquals("警告：禁止广告", result.getData().get("text"));
        assertEquals("WikiKernel:Abusefilter-warning", result.getData().get("page"));
        assertEquals("page", result.getData().get("source"));
    }
}
