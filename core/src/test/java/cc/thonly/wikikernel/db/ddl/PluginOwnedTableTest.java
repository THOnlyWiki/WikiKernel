package cc.thonly.wikikernel.db.ddl;

import cc.thonly.wikikernel.common.data.DbColumn;
import cc.thonly.wikikernel.common.data.DbType;
import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插件自有表（{@code WikiPlugin.schemaEntities()} + {@code PluginDatabase}）的回归测试：
 * 实体元数据 → 建表（幂等）→ JDBC 读写 → 事务回滚，全流程在真实数据源上跑一遍。
 * <p>
 * 这就是「子扩展的实体 / 数据访问留在插件里」需要的平台能力：
 * 插件把 {@code @TableName} 实体声明出来，不用再往 {@code common.data} 塞。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:"
})
class PluginOwnedTableTest {

    @Autowired
    private PluginSchemaDdl schemaDdl;

    @Autowired
    private PluginDatabase database;

    @Test
    void pluginSchemaCreatesTablesAndDatabaseRoundTrips() {
        // 建表幂等：重复调用（插件动态装卸会反复走这里）不报错、不丢数据
        schemaDdl.ensureSchema("test-plugin", List.of(ExampleNote.class));
        schemaDdl.ensureSchema("test-plugin", List.of(ExampleNote.class));

        long id = database.insert(
                "INSERT INTO test_plugin_note(owner_id, note, done, create_at) VALUES (?, ?, ?, ?)",
                7L, "你好，插件表", false, LocalDateTime.now());
        assertTrue(id > 0, "insert 应返回自增主键");

        Optional<DbRow> row = database.queryOne("SELECT * FROM test_plugin_note WHERE id = ?", id);
        assertTrue(row.isPresent());
        assertEquals(7L, row.get().longValue("owner_id", -1));
        assertEquals("你好，插件表", row.get().string("note"));
        assertFalse(row.get().boolValue("done", true));
        assertNotNull(row.get().dateTime("create_at"), "时间列在 SQLite / MySQL / H2 都应能读成 LocalDateTime");
        assertEquals("", row.get().string("missing_column"), "不存在的列按空串读");

        assertEquals(1, database.update("UPDATE test_plugin_note SET done = ? WHERE id = ?", true, id));
        assertTrue(database.queryOne("SELECT done FROM test_plugin_note WHERE id = ?", id)
                .orElseThrow().boolValue("done", false));

        // 事务：抛异常回滚，第二次插入不落库
        assertThrows(IllegalStateException.class, () -> database.transaction(() -> {
            database.insert("INSERT INTO test_plugin_note(owner_id, note, done, create_at) VALUES (?, ?, ?, ?)",
                    8L, "回滚", false, LocalDateTime.now());
            throw new IllegalStateException("rollback");
        }));
        assertEquals(1, database.query("SELECT * FROM test_plugin_note").size(), "事务回滚后只剩第一条");

        assertEquals(1, database.update("DELETE FROM test_plugin_note WHERE id = ?", id));
        assertTrue(database.query("SELECT * FROM test_plugin_note").isEmpty());
    }

    /** 插件自己的表：{@code @TableName} + {@code @TableId(AUTO)} + common 的 {@code @DbColumn}。 */
    @TableName("test_plugin_note")
    static class ExampleNote {

        @TableId(type = IdType.AUTO)
        @DbColumn
        private Long id;

        @DbColumn
        private Long ownerId;

        @DbColumn(type = DbType.LONGTEXT)
        private String note;

        @DbColumn
        private Boolean done;

        @DbColumn(nullable = false)
        private LocalDateTime createAt;
    }
}
