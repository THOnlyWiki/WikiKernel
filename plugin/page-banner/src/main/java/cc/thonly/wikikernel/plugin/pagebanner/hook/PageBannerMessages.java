package cc.thonly.wikikernel.plugin.pagebanner.hook;

import cc.thonly.wikikernel.common.plugin.support.ResourceMessageBundle;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * 插件消息：轮换机制 / 轮播的下拉框文案与前端文案
 * （{@code pageBanner.rotation.*}、{@code pageBanner.transition.*}、{@code pageBanner.interval*}
 * / {@code pageBanner.controls*} / {@code pageBanner.pauseOnHover*} 设置项名称与说明，
 * 以及 {@code pageBanner.prev} / {@code pageBanner.next} / {@code pageBanner.goto} /
 * {@code pageBanner.status} / {@code pageBanner.region} 轮播控件文案）。
 * <p>
 * 文案在插件自己的资源文件里（不再写成 Java 的 {@code Map} 字面量）：
 * <pre>
 * src/main/resources/i18n/messages.properties     中文（默认语言）
 * src/main/resources/i18n/messages_en.properties  英文
 * </pre>
 * 键名风格与迁移前 core 的 {@code messages.properties} / {@code messages_en.properties} 一致，
 * 由 core 的 {@code PluginMessageSource} 合并后供 core 的「特殊:权限配置 → 站点设置」与 {@code /init}
 * 表单（{@code AccessControlSpecialPage} / {@code SetupController}）解析。
 * 注意：表单字段自身的标签（{@code settings.pageBanner*} / {@code setup.pageBanner*}）属于 core 的界面文案，
 * 仍留在 core 的消息文件里（本插件的 {@code pageBanner.interval*} 这类键是给表单**新增**轮播设置项时用的）。
 * <p>
 * {@link #text(String)} 供 {@link PageBannerHook} 生成 {@code data-*} 文案：缺键时返回**翻译键本身**
 * （与 core 的「漏翻显示键名」策略一致，不回落中文）。
 */
public class PageBannerMessages extends ResourceMessageBundle {

    private final MessageSource messageSource;

    public PageBannerMessages() {
        this(null);
    }

    public PageBannerMessages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * 取一条本地化文案；没有消息源或漏翻时返回翻译键本身
     * （core 的 {@code PluginMessageSource} 找不到键时也是返回键名，两者一致）。
     */
    public String text(String key) {
        if (messageSource == null || key == null) {
            return key;
        }
        try {
            String value = messageSource.getMessage(key, new Object[0], key, LocaleContextHolder.getLocale());
            return value == null || value.isBlank() ? key : value;
        } catch (RuntimeException exception) {
            return key;
        }
    }
}
