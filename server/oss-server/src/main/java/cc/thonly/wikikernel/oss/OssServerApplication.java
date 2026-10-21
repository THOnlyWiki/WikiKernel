package cc.thonly.wikikernel.oss;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.nio.file.Files;
import java.nio.file.Path;

/** OSS 服务端入口（独立进程，默认 18081）。 */
@SpringBootApplication
@EnableConfigurationProperties(OssProperties.class)
public class OssServerApplication {

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("data"));
        SpringApplication.run(OssServerApplication.class, args);
    }
}
