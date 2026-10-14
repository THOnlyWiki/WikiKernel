package cc.thonly.wikikernel.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 把 LocalSettings.yaml 中的简写数据库配置翻译成 Spring 标准配置。
 * <p>
 * 类似 MediaWiki 的 LocalSettings.php：使用者只需要在启动目录维护一个
 * {@code LocalSettings.yaml}（由 application.yaml 的 {@code spring.config.import} 加载）：
 *
 * <pre>
 * wikikernel:
 *   database:
 *     type: mysql        # sqlite | mysql | h2
 *     host: 127.0.0.1
 *     port: 3306
 *     name: wikikernel
 *     user: root
 *     password: ${MYSQL_PASSWORD:root}
 * </pre>
 * <p>
 * 本类在 ConfigData 之后执行，把上面这些键翻译成 {@code spring.datasource.*}，
 * 让 DataSource 自动配置、JdbcClient、事务等全部保持标准 Spring Boot 行为。
 * <p>
 * 优先级：命令行参数 / 环境变量 &gt; LocalSettings.yaml &gt; application.yaml 内置默认。
 * 如果使用者直接配置了 {@code spring.datasource.url}，则完全跳过翻译（尊重高级用法）。
 */
public class LocalSettingsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 与 LocalSettings.yaml 中的统一定义保持一致。 */
    private static final String PREFIX = "wikikernel.database.";
    private static final String PROPERTY_SOURCE_NAME = "wikiLocalSettingsDatabase";
    private static final String DATASOURCE_URL = "spring.datasource.url";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getProperty(DATASOURCE_URL) != null) {
            // 使用者已经直接配置了标准数据源，尊重其配置，不做翻译
            return;
        }
        Map<String, Object> translated = translate(environment);
        MutablePropertySources sources = environment.getPropertySources();
        MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, translated);
        // 放在系统环境变量之后：命令行 / 环境变量仍然可以覆盖 LocalSettings
        if (sources.contains("systemEnvironment")) {
            sources.addAfter("systemEnvironment", propertySource);
        } else {
            sources.addLast(propertySource);
        }
    }

    private Map<String, Object> translate(ConfigurableEnvironment environment) {
        String type = environment.getProperty(PREFIX + "type", "sqlite").trim().toLowerCase(Locale.ROOT);
        Map<String, Object> values = new LinkedHashMap<>();
        switch (type) {
            case "sqlite" -> sqlite(environment, values);
            case "mysql" -> mysql(environment, values);
            case "h2" -> h2(environment, values);
            default -> throw new IllegalStateException(
                    "不支持的数据库类型 wikikernel.database.type=" + type + "（可选 sqlite / mysql / h2）");
        }
        return values;
    }

    private void sqlite(ConfigurableEnvironment environment, Map<String, Object> values) {
        String file = environment.getProperty(PREFIX + "file", "./data/wiki.db").trim();
        values.put(DATASOURCE_URL, "jdbc:sqlite:" + file);
        values.put("spring.datasource.driver-class-name", "org.sqlite.JDBC");
        values.put("spring.datasource.username", "");
        values.put("spring.datasource.password", "");
        // SQLite 是单写者模型，连接池保持 1；默认不开启外键，这里每个连接打开
        values.put("spring.datasource.hikari.maximum-pool-size", 1);
        values.put("spring.datasource.hikari.connection-init-sql", "PRAGMA foreign_keys=ON");
    }

    private void h2(ConfigurableEnvironment environment, Map<String, Object> values) {
        String file = environment.getProperty(PREFIX + "file", "./data/wiki").trim();
        values.put(DATASOURCE_URL, "jdbc:h2:file:" + file + ";AUTO_SERVER=TRUE");
        values.put("spring.datasource.driver-class-name", "org.h2.Driver");
        values.put("spring.datasource.username", environment.getProperty(PREFIX + "user", "sa"));
        values.put("spring.datasource.password", environment.getProperty(PREFIX + "password", ""));
        values.put("spring.datasource.hikari.maximum-pool-size", poolSize(environment, 10));
    }

    private void mysql(ConfigurableEnvironment environment, Map<String, Object> values) {
        String host = environment.getProperty(PREFIX + "host", "localhost").trim();
        String port = environment.getProperty(PREFIX + "port", "3306").trim();
        String name = environment.getProperty(PREFIX + "name", "wikikernel").trim();
        values.put(DATASOURCE_URL, "jdbc:mysql://" + host + ":" + port + "/" + name
                + "?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC"
                + "&useSSL=false&allowPublicKeyRetrieval=true");
        values.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        values.put("spring.datasource.username", environment.getProperty(PREFIX + "user", "root"));
        values.put("spring.datasource.password", environment.getProperty(PREFIX + "password", ""));
        values.put("spring.datasource.hikari.maximum-pool-size", poolSize(environment, 10));
    }

    private int poolSize(ConfigurableEnvironment environment, int defaultValue) {
        String value = environment.getProperty(PREFIX + "pool-size");
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("wikikernel.database.pool-size 不是合法数字: " + value, exception);
        }
    }

    @Override
    public int getOrder() {
        // 必须在 ConfigDataEnvironmentPostProcessor 之后执行，此时 LocalSettings.yaml 已加载
        return Ordered.LOWEST_PRECEDENCE;
    }
}
