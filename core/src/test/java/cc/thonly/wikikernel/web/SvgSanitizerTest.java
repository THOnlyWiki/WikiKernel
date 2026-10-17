package cc.thonly.wikikernel.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SvgSanitizerTest {

    @Test
    void removesScriptsEventsForeignObjectsAndUnsafeLinks() {
        String svg = """
                <?xml version="1.0" encoding="UTF-8"?>
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10" onload="alert(1)">
                  <script>alert(2)</script>
                  <rect width="10" height="10" fill="red" onclick="alert(3)"/>
                  <foreignObject><div xmlns="http://www.w3.org/1999/xhtml">x</div></foreignObject>
                  <image href="javascript:alert(4)"/>
                  <use href="#ok"/>
                  <use href="https://evil.example/icon.svg#x"/>
                  <style>@import url(https://evil.example/a.css); .a { fill: red; }</style>
                </svg>
                """;
        String safe = new String(SvgSanitizer.sanitize(svg.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8).toLowerCase();

        assertTrue(safe.startsWith("<svg"), safe);
        assertTrue(safe.contains("<rect"), safe);
        assertTrue(safe.contains("fill=\"red\""), safe);
        assertTrue(safe.contains("href=\"#ok\""), safe);
        assertFalse(safe.contains("script"), safe);
        assertFalse(safe.contains("onload"), safe);
        assertFalse(safe.contains("onclick"), safe);
        assertFalse(safe.contains("foreignobject"), safe);
        assertFalse(safe.contains("javascript:"), safe);
        assertFalse(safe.contains("evil.example"), safe);
        assertFalse(safe.contains("@import"), safe);
    }

    @Test
    void rejectsDoctypeEntitiesAndNonSvgRoot() {
        String dtd = "<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><svg xmlns=\"http://www.w3.org/2000/svg\">&xxe;</svg>";
        assertThrows(SvgSanitizer.InvalidSvgException.class,
                () -> SvgSanitizer.sanitize(dtd.getBytes(StandardCharsets.UTF_8)));

        String html = "<html><body>not svg</body></html>";
        assertThrows(SvgSanitizer.InvalidSvgException.class,
                () -> SvgSanitizer.sanitize(html.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void keepsPlainSvgAndAddsNamespace() {
        String svg = "<svg viewBox=\"0 0 4 4\"><path d=\"M0 0L4 4\"/></svg>";
        String safe = new String(SvgSanitizer.sanitize(svg.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        assertTrue(safe.contains("xmlns=\"http://www.w3.org/2000/svg\""), safe);
        assertTrue(safe.contains("<path"), safe);
    }

    @Test
    void keepsRealLogoShapesGradientsAndText() throws Exception {
        byte[] logo;
        try (var input = getClass().getResourceAsStream("/static/img/wiki-logo.svg")) {
            logo = input.readAllBytes();
        }
        String safe = new String(SvgSanitizer.sanitize(logo), StandardCharsets.UTF_8);
        assertTrue(safe.contains("<circle"), safe);
        assertTrue(safe.contains("linearGradient"), safe);
        assertTrue(safe.contains("<text"), safe);
        assertTrue(safe.contains("W</text>"), safe);
    }
}
