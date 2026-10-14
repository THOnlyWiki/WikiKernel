package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link PluginDatabase} 的 core 实现：直接架在 core 的 {@link DataSource} 上（JDBC），
 * 事务用 core 的 {@link PlatformTransactionManager}。
 * <p>
 * 不做 ORM / 缓存：插件写什么 SQL 就执行什么（{@code ?} 占位符，防注入）；
 * 查询结果统一成 {@link DbRow}（列名小写 + 时间 / 布尔 / 数字类型归一化）。
 */
@Service
public class PluginDatabaseImpl implements PluginDatabase {

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PluginDatabaseImpl(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    @Override
    public long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("没有取到自增主键（表的主键需要是数据库自增列）: " + sql);
        }
        return key.longValue();
    }

    @Override
    public List<DbRow> query(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.query(sql, new ColumnMapRowMapper(), args);
        return rows.stream().map(DbRow::new).toList();
    }

    @Override
    public Optional<DbRow> queryOne(String sql, Object... args) {
        List<DbRow> rows = query(sql, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public <T> T transaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }
}
