package cc.thonly.wikikernel.setup;

/**
 * 安装向导「核心」tab 收集到的设置（内存中，完成后写入 LocalSettings.yaml 并重启生效）。
 *
 * @param siteName      站点名称（{@code wikikernel.site.name}；null = 保持原样，空 = 默认 WikiKernel）
 * @param databaseType  sqlite / h2 / mysql
 * @param databaseFile  sqlite / h2 的数据文件
 * @param dbHost        mysql 主机
 * @param dbPort        mysql 端口
 * @param dbName        mysql 数据库名
 * @param dbUser        mysql 用户
 * @param dbPassword    mysql 密码
 * @param adminUsername 初始账号用户名（同时获得「管理员 + 行政员」）
 * @param adminPassword 初始账号密码（写完 YAML 存 MediaWiki 格式的哈希，见 password-hash）
 * @param adminEmail    初始账号邮箱（可空）
 * @param emailEnabled  是否启用邮件发送
 * @param emailHost     SMTP 主机
 * @param emailPort     SMTP 端口
 * @param emailUsername SMTP 账号
 * @param emailPassword SMTP 密码 / 授权码
 * @param emailFrom     发件人（可空 = 用账号）
 * @param emailBaseUrl  站点地址（可空 = 按请求推导）
 * @param register      是否启用注册（创建账号；默认开启）
 * @param typescript    是否启用 TypeScript 服务端编译（{@code .ts} / {@code .tsx} 页面 → JavaScript；默认关闭）
 * @param typescriptCompileOnSave 保存 TypeScript 页面时是否立即编译（默认关闭）
 * @param cacheEnabled  是否启用页面正文缓存（{@code wikikernel.cache.page.enabled}；默认关闭 = 开箱即安全）
 * @param cacheBackend  缓存后端：{@code auto}（默认，有 Redis 用 Redis，连不上退回进程内 LRU）/ {@code redis} / {@code memory} / {@code none}
 * @param cacheRedisHost Redis 主机（{@code spring.data.redis.host}；默认 127.0.0.1）
 * @param cacheRedisPort Redis 端口（{@code spring.data.redis.port}；默认 6379）
 * @param cacheRedisDatabase Redis 库号（{@code spring.data.redis.database}；默认 0）
 * @param cacheRedisPassword Redis 密码（{@code spring.data.redis.password}；可空 = 无密码；只写配置文件，不写日志）
 * @param cacheRedisKeyPrefix Redis 键前缀（{@code wikikernel.cache.page.redis.key-prefix}；空 = 默认 {@code wikikernel:pagecache:}）
 */
public record CoreSetup(String siteName, String databaseType, String databaseFile,
                        String dbHost, String dbPort, String dbName, String dbUser, String dbPassword,
                        String adminUsername, String adminPassword, String adminEmail,
                        boolean emailEnabled, String emailHost, String emailPort,
                        String emailUsername, String emailPassword, String emailFrom, String emailBaseUrl,


                         boolean register,




                         boolean typescript, boolean typescriptCompileOnSave,

                         boolean cacheEnabled, String cacheBackend,
                         String cacheRedisHost, String cacheRedisPort, String cacheRedisDatabase,
                         String cacheRedisPassword, String cacheRedisKeyPrefix) {
}
