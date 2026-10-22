package cc.thonly.wikikernel.db.ddl;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlSchemaMetadataTest {

    @Test
    void existingTablesAreScopedToTheCurrentSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:ddl_metadata_scope;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA OTHER");
                statement.execute("CREATE TABLE OTHER.wiki_page (id INTEGER)");
            }

            connection.setSchema("PUBLIC");
            Map<String, String> publicTables = DdlSchema.existingTables(connection.getMetaData());
            assertFalse(publicTables.containsKey("wiki_page"), publicTables.toString());

            connection.setSchema("OTHER");
            Map<String, String> otherTables = DdlSchema.existingTables(connection.getMetaData());
            assertTrue(otherTables.containsKey("wiki_page"), otherTables.toString());
        }
    }
}
