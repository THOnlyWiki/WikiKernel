package cc.thonly.wikikernel.wikitext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CssSanitizerTest {

    @Test
    void escapesLessThanSoStyleTagCannotBeClosed() {
        assertEquals("\\3C /style>\nbody {}", CssSanitizer.sanitize("</style>\nbody {}"));
        assertEquals(".x > .y {}", CssSanitizer.sanitize(".x > .y {}"));
        assertEquals("", CssSanitizer.sanitize(null));
        assertEquals("", CssSanitizer.sanitize(""));
    }
}
