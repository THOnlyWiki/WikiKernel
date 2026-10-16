package cc.thonly.wikikernel;

import cc.thonly.wikikernel.config.AuthProperties;
import cc.thonly.wikikernel.config.CorsProperties;
import cc.thonly.wikikernel.config.FeatureProperties;
import cc.thonly.wikikernel.config.FileStorageProperties;
import cc.thonly.wikikernel.config.HttpProperties;
import cc.thonly.wikikernel.config.MailProperties;
import cc.thonly.wikikernel.config.PageCacheProperties;
import cc.thonly.wikikernel.config.PermissionProperties;
import cc.thonly.wikikernel.config.PluginProperties;
import cc.thonly.wikikernel.config.RateLimitProperties;
import cc.thonly.wikikernel.config.SkinProperties;
import cc.thonly.wikikernel.config.SiteProperties;
import cc.thonly.wikikernel.config.TypeScriptProperties;
import cc.thonly.wikikernel.config.SpecialPageProperties;
import cc.thonly.wikikernel.config.SetupProperties;
import cc.thonly.wikikernel.config.TransferProperties;
import cc.thonly.wikikernel.config.UrlProperties;
import cc.thonly.wikikernel.sidebar.SidebarProperties;
import cc.thonly.wikikernel.web.WikiTitleProperties;
import cc.thonly.wikikernel.wikitext.WikitextProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * core 启动入口。
 * <p>
 * main 方法启动 Spring 容器后，PluginManager 会按照 application.yaml 中的
 * {@code wikikernel.plugins} 配置加载并启动插件；
 * MybatisPlusAutoDdl 会根据实体自动建表 / 补字段（类 JPA ddl-auto=update）。
 */
@SpringBootApplication
@EnableConfigurationProperties({PluginProperties.class, SpecialPageProperties.class,
        WikitextProperties.class, SidebarProperties.class, PermissionProperties.class,
        FileStorageProperties.class, AuthProperties.class, SkinProperties.class,
        MailProperties.class, HttpProperties.class,
        SetupProperties.class, FeatureProperties.class, CorsProperties.class,
        RateLimitProperties.class, TransferProperties.class,
        WikiTitleProperties.class, SiteProperties.class, TypeScriptProperties.class,
        PageCacheProperties.class, UrlProperties.class})
@MapperScan("cc.thonly.wikikernel.db.mapper")
public class WikiKernelApplication {

    public static void main(String[] args) {
        SpringApplication.run(WikiKernelApplication.class, args);
    }

}
