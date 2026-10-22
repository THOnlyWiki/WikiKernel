package cc.thonly.wikikernel.common.service;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 插件自有表的数据访问桥（由 core 提供实现，插件可直接 {@code @Autowired} 注入）。
 * <p>
 * 用途：插件用 {@code WikiPlugin.schemaEntities()} 声明自己的表（core 按 {@code @TableName} /
 * {@code @DbColumn} 建表、补列，支持 SQLite / MySQL / H2），再用这个桥读写——**插件专属的实体与
 * 数据访问都留在插件里**，不再往 {@code common.data} / {@code common.service} 塞。
 * <pre>{@code
 *   // 插件服务里
 *   @Autowired PluginDatabase db;
 *
 *   long id = db.insert("INSERT INTO wiki_example(owner_id, note, create_at) VALUES (?, ?, ?)",
 *           userId, note, LocalDateTime.now());
 *   List<DbRow> rows = db.query("SELECT * FROM wiki_example WHERE owner_id = ? ORDER BY id DESC LIMIT ?",
 *           userId, 10);
 *   int changed = db.update("UPDATE wiki_example SET note = ? WHERE id = ?", note, id);
 * }</pre>
 * 约定：
 * <ul>
 *     <li>SQL 用 {@code ?} 占位符（<b>不要把用户输入拼进 SQL</b>）；
 *     {@code LIMIT ?} / {@code LIMIT n} 三种库都支持（MySQL / SQLite / H2）；</li>
 *     <li>列名统一小写读取（见 {@link DbRow}），时间 / 布尔 / 数字类型差异也被 {@link DbRow} 抹平；</li>
 *     <li>写操作请自己保证幂等 / 与 core 的数据一致（这里没有 ORM、没有缓存，就是 JDBC）；</li>
 *     <li>要「多步一致」时用 {@link #transaction(Supplier)} 包起来（基于 core 的事务管理器）。</li>
 * </ul>
 */
public interface PluginDatabase {

    /**
     * 当前 JDBC 数据库产品名（如 {@code SQLite} / {@code MySQL} / {@code MariaDB} / {@code H2}）。
     * <p>
     * 插件只有在确实需要方言差异时才使用它；普通 SQL 应尽量保持可移植。
     */
    String databaseProductName();

    /**
     * 当前 schema 下的用户表名（小写，不含数据库系统表）。
     * <p>
     * 用于插件自己的「按年份分表」之类的表发现，避免直接查询 {@code sqlite_master}、
     * {@code information_schema} 等某一数据库特有的系统表。
     */
    List<String> tableNames();

    /**
     * 执行 INSERT / UPDATE / DELETE。
     *
     * @param sql  SQL（{@code ?} 占位符）
     * @param args 参数
     * @return 受影响行数
     */
    int update(String sql, Object... args);

    /**
     * 执行 INSERT 并返回自增主键（表的主键列必须是数据库自增列）。
     *
     * @param sql  INSERT 语句（{@code ?} 占位符）
     * @param args 参数
     * @return 自增主键
     * @throws IllegalStateException 没有取到自增主键（表 / 列不满足条件）时
     */
    long insert(String sql, Object... args);

    /**
     * 查询多行。
     *
     * @param sql  SELECT 语句（{@code ?} 占位符）
     * @param args 参数
     * @return 行列表（没有结果时为空列表）
     */
    List<DbRow> query(String sql, Object... args);

    /**
     * 查询一行（多行时取第一行）。
     *
     * @param sql  SELECT 语句（{@code ?} 占位符）
     * @param args 参数
     * @return 行；没有结果时为 {@link Optional#empty()}
     */
    Optional<DbRow> queryOne(String sql, Object... args);

    /**
     * 在事务里执行（抛异常回滚，正常返回提交）；嵌套调用复用当前事务。
     *
     * @param work 事务体
     * @param <T>  返回类型
     * @return {@code work} 的返回值
     */
    <T> T transaction(Supplier<T> work);
}
