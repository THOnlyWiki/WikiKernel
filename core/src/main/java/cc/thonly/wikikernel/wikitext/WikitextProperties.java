package cc.thonly.wikikernel.wikitext;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Wikitext 渲染配置：
 *
 * <pre>
 * wikikernel:
 *   wikitext:
 *     template-prefixes: [ "模板:", "Template:" ]
 *     max-template-depth: 20
 * </pre>
 */
@ConfigurationProperties(prefix = "wikikernel.wikitext")
public class WikitextProperties {

    /** 模板命名空间（依次尝试），如 模板:上标。 */
    private List<String> templatePrefixes = new ArrayList<>(List.of("模板:", "Template:"));

    /** 模板最大嵌套深度（防递归炸弹）。 */
    private int maxTemplateDepth = 20;

    public List<String> getTemplatePrefixes() {
        return templatePrefixes;
    }

    public void setTemplatePrefixes(List<String> templatePrefixes) {
        this.templatePrefixes = templatePrefixes;
    }

    public int getMaxTemplateDepth() {
        return maxTemplateDepth;
    }

    public void setMaxTemplateDepth(int maxTemplateDepth) {
        this.maxTemplateDepth = maxTemplateDepth;
    }
}
