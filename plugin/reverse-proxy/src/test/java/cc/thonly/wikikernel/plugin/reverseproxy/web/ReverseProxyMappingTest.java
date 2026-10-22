package cc.thonly.wikikernel.plugin.reverseproxy.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReverseProxyMappingTest {

    @Test
    void dynamicSlugPatternMatchesProxyPaths() {
        PathPatternParser parser = new PathPatternParser();
        PathPattern pattern = parser.parse("/proxy_{slug}/**");
        assertTrue(pattern.matches(PathContainer.parsePath("/proxy_google_doc/document/abc")));
        assertTrue(pattern.matches(PathContainer.parsePath("/proxy_google_doc/")));
    }
}
