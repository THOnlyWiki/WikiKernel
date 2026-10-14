package cc.thonly.wikikernel;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class WikiKernelApplicationTests {

    @Test
    void contextLoads() {
    }

}
