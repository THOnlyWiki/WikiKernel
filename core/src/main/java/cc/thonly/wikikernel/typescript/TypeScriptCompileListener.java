package cc.thonly.wikikernel.typescript;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.WikiPageChangedEvent;
import cc.thonly.wikikernel.service.WikiPageService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 页面变更 → TypeScript 编译（「保存时编译」开启时）。
 * <p>
 * 挂在页面变更事件上（而不是各个保存入口）：网页表单、动作 API（{@code action=edit}）、
 * 导入、AI 代理等所有保存路径都会经过这里，不用逐个接入；未开启编译 / 非
 * {@code .ts} / {@code .tsx} 页面直接跳过（一个字符串判断，开销可忽略）。
 */
@Component
public class TypeScriptCompileListener {

    private final TypeScriptAssetService typeScriptAssetService;
    private final WikiPageService wikiPageService;
    private final FeatureService featureService;

    public TypeScriptCompileListener(TypeScriptAssetService typeScriptAssetService,
                                    WikiPageService wikiPageService,
                                    FeatureService featureService) {
        this.typeScriptAssetService = typeScriptAssetService;
        this.wikiPageService = wikiPageService;
        this.featureService = featureService;
    }

    @EventListener
    public void onPageChanged(WikiPageChangedEvent event) {
        if (event == null || event.deleted() || !TypeScriptAssetService.isTypeScriptPage(event.title())) {
            return;
        }
        if (!featureService.typescriptEnabled() || !featureService.typescriptCompileOnSave()) {
            return;
        }
        WikiPage page = wikiPageService.getByTitle(event.title()).orElse(null);
        if (page == null) {
            return;
        }
        String source = wikiPageService.getLatestContent(page.getId()).orElse("");
        typeScriptAssetService.compileAfterSave(page, source);
    }
}
