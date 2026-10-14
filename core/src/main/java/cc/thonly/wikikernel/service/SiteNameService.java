package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.WikiKernelInfo;
import org.springframework.stereotype.Service;

/**
 * 站点名称：数据库设置 {@code site.name}（启动时由 {@code wikikernel.site.name} 导入），
 * 可在 {@code /init} 安装向导或「特殊:权限配置 → 站点设置」修改。
 * <p>
 * 显示在浏览器标题栏（「页面名 - 站点名」）、页眉图标提示、页脚、搜索框占位符、
 * 邮件主题 / 正文与动作 API 的 {@code sitename} 等；空 = 默认 {@code WikiKernel}。
 */
@Service
public class SiteNameService {

    /** 站点名称的设置键。 */
    public static final String KEY = "site.name";

    /** 默认站点名称（= 软件名）。 */
    public static final String DEFAULT_NAME = WikiKernelInfo.NAME;

    /** 站点名称最大长度。 */
    public static final int MAX_LENGTH = 128;

    private final SiteSettingsService siteSettingsService;

    public SiteNameService(SiteSettingsService siteSettingsService) {
        this.siteSettingsService = siteSettingsService;
    }

    /** 当前站点名称（空 = 默认名）。 */
    public String name() {
        return normalize(siteSettingsService.get(KEY, DEFAULT_NAME));
    }

    /**
     * 规范化站点名称：去掉控制字符、压缩连续空白、去首尾空白、限长；
     * 空字符串 / null = 默认名（{@link #DEFAULT_NAME}）。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return DEFAULT_NAME;
        }
        String cleaned = raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return DEFAULT_NAME;
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }
}
