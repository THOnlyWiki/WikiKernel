package cc.thonly.wikikernel.wikitext;

import cc.thonly.wikikernel.service.WikiPageService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 默认模板解析器：按标题取页面的最新可见版本内容（未删除；隐藏的模板页也可用）。 */
@Component
public class WikiTemplateResolver implements TemplateResolver {

    private final WikiPageService wikiPageService;
    private final TransclusionPolicy transclusionPolicy;

    public WikiTemplateResolver(WikiPageService wikiPageService, TransclusionPolicy transclusionPolicy) {
        this.wikiPageService = wikiPageService;
        this.transclusionPolicy = transclusionPolicy;
    }

    @Override
    public Optional<String> resolve(String title) {
        return wikiPageService.getByTitle(title)
                .filter(page -> transclusionPolicy.canInclude(page.getTitle()))
                .flatMap(page -> wikiPageService.getLatestContent(page.getId()));
    }
}
