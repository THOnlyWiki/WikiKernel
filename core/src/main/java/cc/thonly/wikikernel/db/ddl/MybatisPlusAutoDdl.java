package cc.thonly.wikikernel.db.ddl;

import cc.thonly.wikikernel.common.data.DbColumn;
import cc.thonly.wikikernel.common.data.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 类 JPA {@code ddl-auto=update} 的轻量 Auto-DDL。
 * <p>
 * 基于 MyBatis-Plus 的 {@link TableInfo}（{@code @TableName} 实体）元数据，启动时：
 * <ul>
 *     <li>表不存在 → {@code CREATE TABLE}（含主键、NOT NULL、唯一约束）；</li>
 *     <li>表已存在 → 对比 JDBC 元数据，给缺失的字段执行 {@code ALTER TABLE ... ADD COLUMN}。</li>
 * </ul>
 * 只增不减：不会删列、不会改类型、不会动索引（与 JPA 的 update 策略一样保守）。
 * 支持项目内置的 SQLite / MySQL / MariaDB / H2；语句生成与方言差异在 {@link DdlSchema}。
 * <p>
 * 字段类型可直接用 Java 类型推断，也可以加 {@link DbColumn} 精确控制：
 * {@code @DbColumn(type = DbType.TEXT)}、{@code @DbColumn(length = 512, nullable = false, unique = true)}。
 * <p>
 * 插件自己的表由 {@link PluginSchemaDdl} 负责（同一套 {@link DdlSchema} 规则）。
 * <p>
 * 开关：{@code wikikernel.database.auto-ddl: false} 可关闭。
 */
@Component
@Order(0) // 先建表/补列；旧数据迁移（LegacyWikiPageMigration，@Order(1)）依赖它
public class MybatisPlusAutoDdl implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MybatisPlusAutoDdl.class);

    private final DataSource dataSource;
    private final DataSourceProperties dataSourceProperties;
    private final boolean enabled;

    public MybatisPlusAutoDdl(DataSource dataSource,
                              DataSourceProperties dataSourceProperties,
                              @Value("${wikikernel.database.auto-ddl:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.dataSourceProperties = dataSourceProperties;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) {
            log.info("Auto-DDL 已关闭（wikikernel.database.auto-ddl=false）");
            return;
        }
        prepareEmbeddedDatabaseDirectory();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            DdlSchema.Dialect dialect = DdlSchema.Dialect.from(metaData.getDatabaseProductName());
            Map<String, String> existingTables = DdlSchema.existingTables(metaData);
            List<TableInfo> tableInfos = TableInfoHelper.getTableInfos();
            if (tableInfos.isEmpty()) {
                log.info("Auto-DDL：没有发现 @TableName 实体，跳过");
                return;
            }
            for (TableInfo tableInfo : tableInfos) {
                DdlSchema.Table table = toTable(tableInfo);
                String actualName = existingTables.get(table.name().toLowerCase(Locale.ROOT));
                if (actualName == null) {
                    DdlSchema.execute(connection, DdlSchema.createTableSql(dialect, table));
                    log.info("Auto-DDL：已建表 {}", table.name());
                } else {
                    addMissingColumns(connection, metaData, dialect, table, actualName);
                    warnIdentityMismatch(metaData, tableInfo, actualName);
                }
            }
            log.info("Auto-DDL 检查完成（{}，共 {} 张表）", dataSourceProperties.determineUrl(), tableInfos.size());
        }
    }

    // ==================== 实体元数据 → 表定义 ====================

    private DdlSchema.Table toTable(TableInfo tableInfo) {
        return new DdlSchema.Table(tableInfo.getTableName(), tableInfo.getKeyColumn(), columnsOf(tableInfo));
    }

    private List<DdlSchema.Column> columnsOf(TableInfo tableInfo) {
        List<DdlSchema.Column> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean identity = tableInfo.getIdType() == IdType.AUTO;

        String keyColumn = tableInfo.getKeyColumn();
        if (keyColumn != null) {
            Field keyField = findField(tableInfo.getEntityType(), tableInfo.getKeyProperty());
            DbColumn meta = keyField != null ? keyField.getAnnotation(DbColumn.class) : null;
            columns.add(buildColumn(keyColumn, tableInfo.getKeyType(), meta, true, identity));
            seen.add(keyColumn.toLowerCase(Locale.ROOT));
        }
        for (TableFieldInfo fieldInfo : tableInfo.getFieldList()) {
            if (!seen.add(fieldInfo.getColumn().toLowerCase(Locale.ROOT))) {
                continue;
            }
            columns.add(buildColumn(fieldInfo.getColumn(), fieldInfo.getPropertyType(),
                    fieldInfo.getField().getAnnotation(DbColumn.class), false, false));
        }
        return columns;
    }

    private DdlSchema.Column buildColumn(String name, Class<?> javaType, DbColumn meta,
                                         boolean primaryKey, boolean identity) {
        boolean nullable = meta == null || meta.nullable();
        return new DdlSchema.Column(
                name,
                javaType,
                meta == null ? DbType.AUTO : meta.type(),
                meta == null ? 0 : meta.length(),
                meta == null ? 19 : meta.precision(),
                meta == null ? 4 : meta.scale(),
                primaryKey || !nullable,
                meta == null ? "" : meta.defaultValue(),
                primaryKey || (meta != null && meta.unique()),
                meta == null ? "" : meta.comment(),
                identity);
    }

    private Field findField(Class<?> entityType, String propertyName) {
        if (propertyName == null) {
            return null;
        }
        Class<?> current = entityType;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(propertyName)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    // ==================== ALTER ====================

    private void addMissingColumns(Connection connection, DatabaseMetaData metaData, DdlSchema.Dialect dialect,
                                   DdlSchema.Table table, String actualTableName) throws SQLException {
        Set<String> existingColumns = DdlSchema.existingColumns(metaData, actualTableName);
        for (DdlSchema.Alter alter : DdlSchema.addColumnSql(dialect, table, actualTableName, existingColumns)) {
            DdlSchema.execute(connection, alter.sql());
            log.info("Auto-DDL：{} 新增字段 {}", table.name(), alter.column());
            if (alter.note() != null) {
                log.warn("Auto-DDL：{}", alter.note());
            }
        }
    }

    /** 实体主键声明为 IdType.AUTO，但已有表的主键不是自增列时给出提示（Auto-DDL 不会修改已有列）。 */
    private void warnIdentityMismatch(DatabaseMetaData metaData, TableInfo tableInfo,
                                      String actualTableName) throws SQLException {
        if (tableInfo.getIdType() != IdType.AUTO || tableInfo.getKeyColumn() == null) {
            return;
        }
        boolean autoIncrement = false;
        try (java.sql.ResultSet resultSet = metaData.getColumns(null, null, actualTableName, "%")) {
            while (resultSet.next()) {
                if (resultSet.getString("COLUMN_NAME").equalsIgnoreCase(tableInfo.getKeyColumn())) {
                    autoIncrement = "YES".equalsIgnoreCase(resultSet.getString("IS_AUTOINCREMENT"));
                    break;
                }
            }
        }
        if (!autoIncrement) {
            log.warn("Auto-DDL：{} 的实体主键是 IdType.AUTO，但已有表的主键列 {} 不是自增列；"
                            + "删库重建或手动 ALTER 后才能使用数据库自增",
                    tableInfo.getTableName(), tableInfo.getKeyColumn());
        }
    }

    /** SQLite / H2 文件库不会自动创建父目录，这里先建好。 */
    private void prepareEmbeddedDatabaseDirectory() throws IOException {
        String url = dataSourceProperties.determineUrl();
        String path = null;
        if (url.startsWith("jdbc:sqlite:")) {
            path = url.substring("jdbc:sqlite:".length());
        } else if (url.startsWith("jdbc:h2:file:")) {
            path = url.substring("jdbc:h2:file:".length());
        }
        if (path == null) {
            return;
        }
        int end = path.length();
        int semicolon = path.indexOf(';');
        if (semicolon >= 0) {
            end = Math.min(end, semicolon);
        }
        int question = path.indexOf('?');
        if (question >= 0) {
            end = Math.min(end, question);
        }
        String cleanPath = path.substring(0, end);
        if (cleanPath.isBlank() || cleanPath.equals(":memory:")) {
            return;
        }
        Path parent = Path.of(cleanPath).toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
