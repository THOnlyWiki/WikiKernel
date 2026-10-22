package cc.thonly.wikikernel.db.ddl;

import cc.thonly.wikikernel.common.data.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlSchemaTest {

    @Test
    void quotesStringDefaultsForMariaDb() {
        DdlSchema.Table table = new DdlSchema.Table("wiki_file", "id", List.of(
                new DdlSchema.Column("id", Long.class, DbType.AUTO, 0, 19, 4,
                        true, "", true, "", true),
                new DdlSchema.Column("license", String.class, DbType.AUTO, 80, 19, 4,
                        false, "共用领域协议", false, "", false),
                new DdlSchema.Column("version", Integer.class, DbType.AUTO, 0, 19, 4,
                        false, "1", false, "", false),
                new DdlSchema.Column("icon", String.class, DbType.AUTO, 16, 19, 4,
                        false, "'🎁'", false, "", false),
                new DdlSchema.Column("read", Boolean.class, DbType.AUTO, 0, 19, 4,
                        false, "0", false, "", false)
        ));

        String sql = DdlSchema.createTableSql(DdlSchema.Dialect.MYSQL, table);
        assertTrue(sql.contains("`license` VARCHAR(80) DEFAULT '共用领域协议'"), sql);
        assertFalse(sql.contains("DEFAULT 共用领域协议"), sql);
        assertTrue(sql.contains("`version` INT DEFAULT 1"), sql);
        assertTrue(sql.contains("`icon` VARCHAR(16) DEFAULT '🎁'"), sql);
        assertFalse(sql.contains("DEFAULT ''🎁''"), sql);
        assertTrue(sql.contains("`read` TINYINT(1) DEFAULT 0"), sql);
        assertTrue(sql.contains("DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"), sql);

        List<DdlSchema.Alter> alters = DdlSchema.addColumnSql(
                DdlSchema.Dialect.MYSQL, table, "wiki_file", Set.of("id"));
        assertTrue(alters.stream().anyMatch(alter ->
                alter.sql().contains("`license` VARCHAR(80) DEFAULT '共用领域协议'")));
    }
}
