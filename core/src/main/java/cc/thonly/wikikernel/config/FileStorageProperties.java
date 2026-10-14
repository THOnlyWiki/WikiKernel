package cc.thonly.wikikernel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件上传配置（LocalSettings.yaml 的 {@code wikikernel.run-path} 等）。
 */
@Data
@ConfigurationProperties(prefix = "wikikernel")
public class FileStorageProperties {

    /**
     * 运行文件目录：上传文件的存放位置（相对启动目录，默认 {@code ./run/files}）。
     * <p>
     * 与 MediaWiki 的 {@code $wgUploadDirectory} 类似；通过 {@code /files/<storedName>} 提供访问。
     */
    private String runPath = "./run/files";

    /** 单个上传文件大小上限（MB，默认 20；同时受 spring.servlet.multipart 限制）。 */
    private int maxFileSizeMb = 20;
}
