package cc.thonly.wikikernel.oss;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OSS 服务端配置。 */
@ConfigurationProperties(prefix = "oss")
public class OssProperties {

    /** 写接口共享密钥；查看接口不需要。 */
    private String key = "change-me";

    /** 原图 / 缩略图根目录。 */
    private String storageDir = "./data/oss-files";

    /** ImageMagick 可执行文件；空 = 自动查找 magick。 */
    private String magick = "";

    /** WebP 质量。 */
    private int thumbnailQuality = 82;

    /** 公开读取域名（记录 / 文档用）。 */
    private String publicBaseUrl = "http://127.0.0.1:18081";

    /** 可选 CDN 读取域名。 */
    private String cdnBaseUrl = "";

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public String getMagick() {
        return magick;
    }

    public void setMagick(String magick) {
        this.magick = magick;
    }

    public int getThumbnailQuality() {
        return thumbnailQuality;
    }

    public void setThumbnailQuality(int thumbnailQuality) {
        this.thumbnailQuality = thumbnailQuality;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getCdnBaseUrl() {
        return cdnBaseUrl;
    }

    public void setCdnBaseUrl(String cdnBaseUrl) {
        this.cdnBaseUrl = cdnBaseUrl;
    }
}
