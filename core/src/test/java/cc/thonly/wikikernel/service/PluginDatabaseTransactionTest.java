package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.service.PluginDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"wikikernel.database.file=:memory:", "spring.datasource.hikari.maximum-pool-size=1"})
class PluginDatabaseTransactionTest {
    @Autowired PluginDatabase database;
    @Test void metadataReusesTransactionConnectionAndDoesNotCloseOrCommitIt() {
        database.update("CREATE TABLE plugin_metadata_transaction_test (id INTEGER)");
        assertTimeout(Duration.ofSeconds(5), () -> {
            assertThrows(IllegalStateException.class, () -> database.transaction(() -> {
                database.update("INSERT INTO plugin_metadata_transaction_test VALUES (1)");
                assertEquals("SQLite", database.databaseProductName());
                assertTrue(database.tableNames().contains("plugin_metadata_transaction_test"));
                database.update("INSERT INTO plugin_metadata_transaction_test VALUES (2)");
                throw new IllegalStateException("Roll back both writes");
            }));
        });
        assertEquals(0, database.queryOne("SELECT COUNT(*) AS total FROM plugin_metadata_transaction_test").orElseThrow().longValue("total", 0));
    }
}
