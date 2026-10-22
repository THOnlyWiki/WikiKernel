package cc.thonly.wikikernel.db.ddl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 旧数据迁移：把「一行 = 一个页面，正文在 wiki_page.content」的旧结构，
 * 迁移成「wiki_page 存页面身份 + wiki_revision 存版本正文」的新结构。
 * <p>
 * 幂等、可重复执行：
 * <ul>
 *     <li>新库（没有 content 列）直接跳过；</li>
 *     <li>只处理 {@code latest_revision_id} 为空且 {@code content} 非空的页面；</li>
 *     <li>迁移完成后移除旧结构专有列（{@code page_identifier} 是 NOT NULL，不移除会挡住新插入）。</li>
 * </ul>
 * 由 {@code wikikernel.database.auto-ddl: false} 一并关闭。
 */
@Component
@Order(1) // 在 Auto-DDL（@Order(0)）建表/补列之后执行
public class LegacyWikiPageMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyWikiPageMigration.class);

    private static final String TABLE = "wiki_page";

    private final DataSource dataSource;
    private final boolean enabled;

    public LegacyWikiPageMigration(DataSource dataSource,
                                   @Value("${wikikernel.database.auto-ddl:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!hasColumn(connection, TABLE, "content")) {
                return; // 新结构，无需迁移
            }
            migrateRows(connection);
            dropLegacyColumn(connection, "page_identifier");
            dropLegacyColumn(connection, "content");
            dropLegacyColumn(connection, "user_id");
        }
    }

    private void migrateRows(Connection connection) throws SQLException {
        List<LegacyRow> rows = loadLegacyRows(connection);
        if (rows.isEmpty()) {
            return;
        }
        connection.setAutoCommit(false);
        try {
            for (LegacyRow row : rows) {
                long revisionId = insertRevision(connection, row);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + TABLE + " SET latest_revision_id = ? WHERE id = ?")) {
                    update.setLong(1, revisionId);
                    update.setLong(2, row.id());
                    update.executeUpdate();
                }
            }
            connection.commit();
            log.info("旧数据迁移：{} 个页面已生成初始版本（wiki_revision）", rows.size());
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private List<LegacyRow> loadLegacyRows(Connection connection) throws SQLException {
        String sql = "SELECT id, content, user_id, update_at, create_at FROM " + TABLE
                + " WHERE latest_revision_id IS NULL AND content IS NOT NULL";
        List<LegacyRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                LocalDateTime fallback = LocalDateTime.now();
                rows.add(new LegacyRow(
                        resultSet.getLong("id"),
                        resultSet.getString("content"),
                        resultSet.getObject("user_id") == null ? null : resultSet.getLong("user_id"),
                        toDateTime(resultSet.getObject("update_at"),
                                toDateTime(resultSet.getObject("create_at"), fallback))));
            }
        }
        return rows;
    }

    private long insertRevision(Connection connection, LegacyRow row) throws SQLException {
        String sql = "INSERT INTO wiki_revision (page_id, content, user_id, comment, deleted, create_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, row.id());
            statement.setString(2, row.content());
            if (row.userId() == null) {
                statement.setNull(3, Types.BIGINT);
            } else {
                statement.setLong(3, row.userId());
            }
            statement.setString(4, "从旧版结构迁移");
            statement.setBoolean(5, false);
            statement.setObject(6, row.createAt());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("旧数据迁移失败：未能获取 wiki_revision 自增主键（页面 " + row.id() + "）");
    }

    private void dropLegacyColumn(Connection connection, String column) {
        if (!hasColumn(connection, TABLE, column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + TABLE + " DROP COLUMN " + column);
            log.info("旧数据迁移：已移除 {}.{}（新结构不再使用）", TABLE, column);
        } catch (SQLException e) {
            log.warn("旧数据迁移：无法移除 {}.{}，必要时请手动处理：{}", TABLE, column, e.getMessage());
        }
    }

    /** 用 JDBC 元数据探测列，避免故意查询不存在的列让 MariaDB 驱动记一条 1054 错误。 */
    private boolean hasColumn(Connection connection, String table, String column) {
        try {
            Set<String> columns = DdlSchema.existingColumns(connection.getMetaData(), table);
            return columns.contains(column.toLowerCase(Locale.ROOT));
        } catch (SQLException e) {
            return false;
        }
    }

    private static LocalDateTime toDateTime(Object value, LocalDateTime fallback) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return LocalDateTime.parse(text.replace(' ', 'T'));
            } catch (RuntimeException ignored) {
                // 保持 fallback
            }
        }
        return fallback;
    }

    private record LegacyRow(long id, String content, Long userId, LocalDateTime createAt) {
    }
}
