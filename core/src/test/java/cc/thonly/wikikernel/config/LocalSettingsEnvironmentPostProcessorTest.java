package cc.thonly.wikikernel.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalSettingsEnvironmentPostProcessorTest {

    @Test
    void translatesNativeMariaDbConnection() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("wikikernel.database.type", "mariadb")
                .withProperty("wikikernel.database.host", "db.example.com")
                .withProperty("wikikernel.database.port", "3307")
                .withProperty("wikikernel.database.name", "wiki")
                .withProperty("wikikernel.database.user", "wiki_user")
                .withProperty("wikikernel.database.password", "secret")
                .withProperty("wikikernel.database.pool-size", "12");

        new LocalSettingsEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertEquals("jdbc:mariadb://db.example.com:3307/wiki?sslMode=disable&allowPublicKeyRetrieval=true",
                environment.getProperty("spring.datasource.url"));
        assertEquals("org.mariadb.jdbc.Driver",
                environment.getProperty("spring.datasource.driver-class-name"));
        assertEquals("wiki_user", environment.getProperty("spring.datasource.username"));
        assertEquals("secret", environment.getProperty("spring.datasource.password"));
        assertEquals("12", environment.getProperty("spring.datasource.hikari.maximum-pool-size"));
    }

    @Test
    void keepsMySqlTranslation() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("wikikernel.database.type", "mysql")
                .withProperty("wikikernel.database.host", "localhost")
                .withProperty("wikikernel.database.name", "wiki");

        new LocalSettingsEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertEquals("jdbc:mysql://localhost:3306/wiki?useUnicode=true&characterEncoding=UTF-8"
                        + "&connectionTimeZone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                environment.getProperty("spring.datasource.url"));
        assertEquals("com.mysql.cj.jdbc.Driver",
                environment.getProperty("spring.datasource.driver-class-name"));
    }

    @Test
    void rejectsUnknownDatabaseType() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("wikikernel.database.type", "oracle");
        assertThrows(IllegalStateException.class,
                () -> new LocalSettingsEnvironmentPostProcessor().postProcessEnvironment(environment, null));
    }
}
