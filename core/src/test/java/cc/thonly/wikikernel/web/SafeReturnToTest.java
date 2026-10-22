package cc.thonly.wikikernel.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SafeReturnToTest {

    @Test
    void rejectsBackslashesControlsAndProtocolRelativePaths() {
        assertEquals("/wiki/首页", SafeReturnTo.path("/wiki/首页", "/"));
        assertEquals("/", SafeReturnTo.path("/\\evil.example", "/"));
        assertEquals("/", SafeReturnTo.path("//evil.example", "/"));
        assertEquals("/", SafeReturnTo.path("/%5cevil.example", "/"));
        assertEquals("/", SafeReturnTo.path("/%2fevil.example", "/"));
        assertEquals("/", SafeReturnTo.path("/ok\r\nLocation: https://evil.example", "/"));
        assertEquals("/", SafeReturnTo.path("https://evil.example", "/"));
    }

    @Test
    void acceptsSafeTitleOrPath() {
        assertEquals("/wiki/特殊:参数设置", SafeReturnTo.titleOrPath("特殊:参数设置", "/wiki/特殊:参数设置"));
        assertEquals("/wiki/首页", SafeReturnTo.titleOrPath("/wiki/首页", "/wiki/特殊:参数设置"));
        assertEquals("/wiki/特殊:参数设置", SafeReturnTo.titleOrPath("https://evil.example", "/wiki/特殊:参数设置"));
        assertEquals("/wiki/特殊:参数设置", SafeReturnTo.titleOrPath("特殊:参数设置\\evil", "/wiki/特殊:参数设置"));
    }
}
