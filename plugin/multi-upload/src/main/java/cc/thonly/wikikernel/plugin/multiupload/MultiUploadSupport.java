package cc.thonly.wikikernel.plugin.multiupload;

import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * 多文件批量上传的公共部分：开关（站点设置 {@code features.multi-upload}）与本地化文案。
 */
public class MultiUploadSupport {

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;

    public MultiUploadSupport(SiteConfig siteConfig, MessageSource messageSource) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
    }

    /** 多文件批量上传是否开启（默认关闭）。 */
    public boolean enabled() {
        return siteConfig != null && siteConfig.getBoolean(MultiUploadPlugin.ENABLED_KEY, false);
    }

    /** 取本地化文案（core 的 MessageSource 已合并插件消息；缺失时用兜底文案）。 */
    public String message(String key, String fallback) {
        if (messageSource == null) {
            return fallback;
        }
        try {
            return messageSource.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
