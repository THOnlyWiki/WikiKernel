package cc.thonly.wikikernel.plugin.reverseproxy;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.reverseproxy.model.ReverseProxyRoute;
import cc.thonly.wikikernel.plugin.reverseproxy.service.ReverseProxyConfigService;
import cc.thonly.wikikernel.plugin.reverseproxy.web.ReverseProxySpecialPage;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;
import org.thymeleaf.messageresolver.IMessageResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真模板 + 真 Thymeleaf：确认管理页模板能被解析并渲染出表单 / 路由。 */
class ReverseProxySpecialPageTemplateTest {

    private static final String LAYOUT_NAME = "test-layout";

    @Test
    void rendersRouteTableAndFormForAdmin() {
        MemorySiteConfig site = new MemorySiteConfig();
        ObjectMapper mapper = new ObjectMapper();
        ReverseProxyConfigService config = new ReverseProxyConfigService(site, mapper);
        config.saveRoutes(java.util.List.of(new ReverseProxyRoute(
                "google_doc", "https://docs.google.com", "public", true,
                true, true, "GET,HEAD", 30, "Google Docs", false)));
        Viewer admin = new Viewer(1, "管理员", false, Set.of("管理员"), false);
        ReverseProxySupport support = new ReverseProxySupport(new BundleMessageSource(), config);
        ReverseProxySpecialPage page = new ReverseProxySpecialPage(config, support,
                new AdminAccess(), new FixedCurrentUser(admin));

        Map<String, Object> model = new LinkedHashMap<>();
        Locale locale = Locale.SIMPLIFIED_CHINESE;
        try {
            LocaleContextHolder.setLocale(locale);
            page.populate(new SpecialPageRequest("反向代理", Map.of(), null), model);
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
        model.put("title", "反向代理");
        model.put("skin", Map.of("layout", LAYOUT_NAME));

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolvers(Set.of(resolver));
        engine.setMessageResolver(bundleMessages(locale));
        engine.setLinkBuilder(new StandardLinkBuilder() {
            @Override
            protected String computeContextPath(IExpressionContext context, String base,
                                                Map<String, Object> parameters) {
                return "";
            }
        });
        String html = engine.process("special/reverse-proxy", new Context(locale, model));

        assertTrue(html.contains("id=\"reverse-proxy-form\""), html);
        assertTrue(html.contains("/proxy_google_doc/"), html);
        assertTrue(html.contains("data-slug=\"google_doc\""), html);
        assertTrue(html.contains("Google Docs"), html);
        assertTrue(html.contains("安全边界"), html);
        assertTrue(!html.contains("??reverseProxy"), html);
    }

    private static IMessageResolver bundleMessages(Locale locale) {
        Map<String, Map<String, String>> messages = new ReverseProxyMessages().messages();
        Map<String, String> texts = messages.getOrDefault(locale.getLanguage(),
                messages.getOrDefault("zh", Map.of()));
        return new IMessageResolver() {
            @Override
            public String getName() {
                return "testBundle";
            }

            @Override
            public Integer getOrder() {
                return 1;
            }

            @Override
            public String resolveMessage(ITemplateContext context, Class<?> origin, String key,
                                         Object[] messageParameters) {
                String text = texts.get(key);
                if (text == null) {
                    return null;
                }
                return messageParameters == null || messageParameters.length == 0
                        ? text : MessageFormat.format(text, messageParameters);
            }

            @Override
            public String createAbsentMessageRepresentation(ITemplateContext context, Class<?> origin,
                                                            String key, Object[] messageParameters) {
                return "??" + key + "??";
            }
        };
    }

    private static final class MemorySiteConfig implements SiteConfig {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key, String defaultValue) {
            return values.getOrDefault(key, defaultValue);
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            String value = values.get(key);
            return value == null ? defaultValue : Boolean.parseBoolean(value);
        }

        @Override
        public void set(String key, String value) {
            values.put(key, value);
        }
    }

    private static final class AdminAccess implements AccessCheck {
        @Override
        public boolean can(AccessAction action, String target, Viewer viewer) {
            return action == AccessAction.PROTECT;
        }

        @Override
        public Optional<String> blockNotice(Viewer viewer) {
            return Optional.empty();
        }
    }

    private static final class FixedCurrentUser implements CurrentUser {
        private final Viewer viewer;

        private FixedCurrentUser(Viewer viewer) {
            this.viewer = viewer;
        }

        @Override
        public Viewer viewer() {
            return viewer;
        }
    }

    private static final class BundleMessageSource implements MessageSource {
        private final Map<String, Map<String, String>> messages = new ReverseProxyMessages().messages();

        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            String text = messages.getOrDefault(locale.getLanguage(), messages.get("zh")).get(code);
            if (text == null) {
                return defaultMessage;
            }
            return args == null || args.length == 0 ? text : MessageFormat.format(text, args);
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            return getMessage(code, args, code, locale);
        }

        @Override
        public String getMessage(org.springframework.context.MessageSourceResolvable resolvable,
                                 Locale locale) {
            return getMessage(resolvable.getCodes()[0], resolvable.getArguments(), "?", locale);
        }
    }
}
