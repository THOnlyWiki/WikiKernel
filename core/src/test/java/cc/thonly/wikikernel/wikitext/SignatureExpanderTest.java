package cc.thonly.wikikernel.wikitext;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignatureExpanderTest {

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 23, 14, 6);

    @Test
    void expandsThreeFourAndFiveTildes() {
        assertEquals("[[用户:夜樱|夜樱]]",
                SignatureExpander.expand("~~~", "夜樱", "", false, TIME));
        assertEquals("[[用户:夜樱|夜樱]] 2026-10-23 14:06",
                SignatureExpander.expand("~~~~", "夜樱", "", false, TIME));
        assertEquals("2026-10-23 14:06",
                SignatureExpander.expand("~~~~~", "夜樱", "", false, TIME));
        assertEquals("--[[用户:夜樱|夜樱]] 2026-10-23 14:06",
                SignatureExpander.expand("--~~~~", "夜樱", "", false, TIME));
    }

    @Test
    void usesCustomSignatureWithAndWithoutWikitext() {
        assertEquals("[[用户:夜樱|我的签名]]",
                SignatureExpander.expand("~~~", "夜樱", "我的签名", false, TIME));
        assertEquals("'''粗体'''",
                SignatureExpander.expand("~~~", "夜樱", "'''粗体'''", true, TIME));
    }

    @Test
    void leavesLiteralBlocksAlone() {
        assertEquals("<nowiki>~~~</nowiki>",
                SignatureExpander.expand("<nowiki>~~~</nowiki>", "夜樱", "", false, TIME));
        assertEquals("<pre>~~~~</pre>",
                SignatureExpander.expand("<pre>~~~~</pre>", "夜樱", "", false, TIME));
        assertEquals("<!-- ~~~ -->",
                SignatureExpander.expand("<!-- ~~~ -->", "夜樱", "", false, TIME));
    }
}
