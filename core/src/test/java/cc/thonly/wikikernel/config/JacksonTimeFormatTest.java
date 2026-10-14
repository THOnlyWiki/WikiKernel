package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.common.data.WikiPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证全局 JSON 时间格式：LocalDateTime 输出 yyyy-MM-dd HH:mm:ss。 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class JacksonTimeFormatTest {

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void localDateTimeUsesConfiguredJsonFormat() throws Exception {
        WikiPage page = new WikiPage();
        page.setTitle("测试");
        page.setUpdateAt(LocalDateTime.of(2026, 10, 4, 16, 30, 0));

        String json = jsonMapper.writeValueAsString(page);
        assertTrue(json.contains("\"updateAt\":\"2026-10-04 16:30:00\""), json);
    }
}
