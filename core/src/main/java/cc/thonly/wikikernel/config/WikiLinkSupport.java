package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.common.web.WikiUrls;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * 把 Thymeleaf 的 {@code @{...}} 链接改成配置的页面路径前缀：
 * 模板里写死了 300 多处 {@code @{/wiki/...}}，这里统一在链接生成时重写
 * （{@code /wiki/...} → 配置前缀，如裸根部署的 {@code /首页}；含特殊页前缀替换），
 * 不用改任何模板。
 */
@Component
public class WikiLinkSupport {

    private final SpringTemplateEngine templateEngine;

    public WikiLinkSupport(SpringTemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    @PostConstruct
    void registerLinkBuilder() {
        templateEngine.setLinkBuilder(new WikiLinkBuilder());
    }

    /** 在标准链接生成（含 URL 编码）之后重写前缀（context path / 查询串保持原样）。 */
    static final class WikiLinkBuilder extends StandardLinkBuilder {

        @Override
        protected String processLink(IExpressionContext context, String link) {
            return WikiUrls.rewriteTemplateLink(super.processLink(context, link));
        }
    }
}
