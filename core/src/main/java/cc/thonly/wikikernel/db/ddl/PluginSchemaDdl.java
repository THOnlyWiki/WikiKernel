package cc.thonly.wikikernel.db.ddl;

import cc.thonly.wikikernel.common.data.DbColumn;
import cc.thonly.wikikernel.common.data.DbType;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
 * 插件自有表的 Auto-DDL：插件在 {@code WikiPlugin.schemaEntities()} 里声明自己的
 * {@code @TableName} 实体，core 在插件启动（{@code onStart} 之前）按与 core 相同的规则
 * 建表 / 补列（{@link DdlSchema}，支持 SQLite / MySQL / H2，只增不减）。
 * <p>
 * 这样<b>插件的数据表与实体都属于插件自己</b>（不再往 {@code common.data} 里塞插件专属实体），
 * 数据访问用 {@link PluginDatabase}（JDBC 桥），
 * 表结构与 core 的 Auto-DDL 保持同一套方言 / 变更策略；插件反复装卸（动态 reload）时是幂等的。
 * <p>
 * 尊重总开关 {@code wikikernel.database.auto-ddl: false}（关闭时插件表需要运维手工建）；
 * 结构初始化失败会让该插件启动失败（只影响这个插件，应用继续）。
 */
@Component
public class PluginSchemaDdl {

    private static final Logger log = LoggerFactory.getLogger(PluginSchemaDdl.class);

    private final DataSource dataSource;
    private final boolean enabled;

    public PluginSchemaDdl(DataSource dataSource,
                           @Value("${wikikernel.database.auto-ddl:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    /**
     * 给插件声明的实体建表 / 补列（幂等；没有声明实体时直接返回）。
     *
     * @param pluginId 插件 id（日志 / 报错用）
     * @param entities 插件自己的 {@code @TableName} 实体类
     */
    public void ensureSchema(String pluginId, List<Class<?>> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        if (!enabled) {
            log.warn("Auto-DDL 已关闭（wikikernel.database.auto-ddl=false）：插件 {} 的表不会自动创建 / 补列",
                    pluginId);
            return;
        }
        List<DdlSchema.Table> tables = new ArrayList<>();
        for (Class<?> entity : entities) {
            DdlSchema.Table table = toTable(entity);
            if (table != null) {
                tables.add(table);
            }
        }
        if (tables.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            DdlSchema.Dialect dialect = DdlSchema.Dialect.from(metaData.getDatabaseProductName());
            Map<String, String> existingTables = DdlSchema.existingTables(metaData);
            for (DdlSchema.Table table : tables) {
                String actualName = existingTables.get(table.name().toLowerCase(Locale.ROOT));
                if (actualName == null) {
                    DdlSchema.execute(connection, DdlSchema.createTableSql(dialect, table));
                    log.info("插件建表：{}（插件 {}）", table.name(), pluginId);
                    continue;
                }
                Set<String> columns = DdlSchema.existingColumns(metaData, actualName);
                for (DdlSchema.Alter alter : DdlSchema.addColumnSql(dialect, table, actualName, columns)) {
                    DdlSchema.execute(connection, alter.sql());
                    log.info("插件补列：{} 新增字段 {}（插件 {}）", table.name(), alter.column(), pluginId);
                    if (alter.note() != null) {
                        log.warn("插件建表：{}", alter.note());
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("插件 " + pluginId + " 的表结构初始化失败: " + exception.getMessage(),
                    exception);
        }
    }

    // ==================== 实体元数据（反射） ====================

    /**
     * 反射读 {@code @TableName} / {@code @TableId} / {@code @TableField} / {@code @DbColumn}：
     * 列名缺省按 MyBatis-Plus 的约定（驼峰 → 下划线），{@code @TableField(exist = false)} 的字段跳过。
     */
    private DdlSchema.Table toTable(Class<?> entity) {
        TableName tableName = entity.getAnnotation(TableName.class);
        if (tableName == null || tableName.value().isBlank()) {
            log.warn("插件实体缺少 @TableName，已跳过: {}", entity.getName());
            return null;
        }
        String table = tableName.value().trim();
        String keyColumn = null;
        List<DdlSchema.Column> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> current = entity; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())
                        || Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                TableField tableField = field.getAnnotation(TableField.class);
                if (tableField != null && !tableField.exist()) {
                    continue;
                }
                TableId tableId = field.getAnnotation(TableId.class);
                String column = columnName(field, tableId, tableField);
                if (!seen.add(column.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                boolean primaryKey = tableId != null;
                boolean identity = primaryKey && tableId.type() == IdType.AUTO;
                if (primaryKey) {
                    keyColumn = column;
                }
                columns.add(buildColumn(column, field.getType(), field.getAnnotation(DbColumn.class),
                        primaryKey, identity));
            }
        }
        if (keyColumn == null) {
            // MyBatis-Plus 的默认约定：没有 @TableId 时用名为 id 的列作主键
            for (DdlSchema.Column column : columns) {
                if (column.name().equalsIgnoreCase("id")) {
                    keyColumn = column.name();
                    break;
                }
            }
        }
        return new DdlSchema.Table(table, keyColumn, columns);
    }

    private String columnName(Field field, TableId tableId, TableField tableField) {
        if (tableId != null && !tableId.value().isBlank()) {
            return tableId.value().trim();
        }
        if (tableField != null && !tableField.value().isBlank()) {
            return tableField.value().trim();
        }
        return snakeCase(field.getName());
    }

    /** 驼峰 → 下划线（MyBatis-Plus 的默认列名约定）。 */
    static String snakeCase(String name) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (Character.isUpperCase(character)) {
                if (i > 0 && (Character.isLowerCase(name.charAt(i - 1))
                        || (i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1))))) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(character));
            } else {
                result.append(character);
            }
        }
        return result.toString();
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
}
