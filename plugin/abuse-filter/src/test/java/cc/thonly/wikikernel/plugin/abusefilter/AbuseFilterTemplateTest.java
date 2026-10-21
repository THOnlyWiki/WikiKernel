package cc.thonly.wikikernel.plugin.abusefilter;

import cc.thonly.wikikernel.plugin.abusefilter.data.WikiAbuseFilter;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 编辑页模板能实际渲染（防止 Thymeleaf 属性 / 表达式回归）。 */
class AbuseFilterTemplateTest {

    @Test
    void editFormRendersAllRequiredSections() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");

        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n.messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setUseCodeAsDefaultMessage(true);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);

        WikiAbuseFilter filter = new WikiAbuseFilter();
        filter.setId(12L);
        filter.setDescription("测试过滤器");
        filter.setPattern("pwords := \"a|b\"; action == \"edit\"");
        filter.setNotes("备注");
        filter.setWarnMessage("警告消息");
        filter.setDisallowPage("MediaWiki:AbuseFilter-disallowed");
        filter.setThrottleCount(3);
        filter.setThrottlePeriod(60);
        filter.setBlockAnonDays(1);
        filter.setBlockUserDays(7);

        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        JakartaServletWebApplication application =
                JakartaServletWebApplication.buildApplication(servletContext);
        WebContext context = new WebContext(application.buildExchange(request, response), Locale.CHINA);
        context.setVariable("skin", Map.of("layout", "test-layout"));
        context.setVariable("title", "特殊:滥用过滤器");
        context.setVariable("mode", "edit");
        context.setVariable("disabled", false);
        context.setVariable("abuseFilter", filter);
        context.setVariable("selectedActions", List.of("warn", "throttle", "block", "tag"));
        context.setVariable("actionOptions", List.of());

        String html = engine.process("special/abuse-filter", context);
        assertTrue(html.contains("编辑滥用过滤器"), html);
        assertTrue(html.contains("过滤器参数"), html);
        assertTrue(html.contains("条件："), html);
        assertTrue(html.contains("name=\"notes\""), html);
        assertTrue(html.contains("name=\"warnMessage\""), html);
        assertTrue(html.contains("name=\"disallowPage\""), html);
        assertTrue(html.contains("name=\"blockAnonDays\""), html);
        assertTrue(html.contains("name=\"blockUserDays\""), html);
        assertTrue(html.contains("name=\"actions\""), html);
        assertTrue(html.contains("id=\"abuse-filter-insert\""), html);
        assertTrue(html.contains("abuse-message-preview"), html);
        assertTrue(html.contains("abuse-message-page-link"), html);
        assertFalse(html.contains("th:"), html);
    }
}
