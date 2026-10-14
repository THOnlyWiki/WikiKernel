package cc.thonly.wikikernel.plugin.confirmedit;

import cc.thonly.wikikernel.common.extension.SessionAttributes;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.confirmedit.service.CaptchaChallenge;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * ConfirmEdit 的公共部分：站点设置（开关 / 免验证组）、随机加法题与会话状态、本地化文案。
 */
public class ConfirmEditSupport {

    private final ConfirmEditSettings settings;
    private final CaptchaChallenge challenge;
    private final MessageSource messageSource;

    public ConfirmEditSupport(SiteConfig siteConfig, MessageSource messageSource) {
        this(new ConfirmEditSettings(siteConfig), new CaptchaChallenge(), messageSource);
    }

    public ConfirmEditSupport(ConfirmEditSettings settings, CaptchaChallenge challenge,
                              MessageSource messageSource) {
        this.settings = settings == null ? new ConfirmEditSettings(null) : settings;
        this.challenge = challenge == null ? new CaptchaChallenge() : challenge;
        this.messageSource = messageSource;
    }

    /** 功能开关（{@code features.confirm-edit}，默认关闭）。 */
    public boolean enabled() {
        return settings.enabled();
    }

    /** 该用户是否需要验证码（功能关闭 / 命中免验证组时不需要；匿名用户需要）。 */
    public boolean appliesTo(Viewer viewer) {
        return settings.appliesTo(viewer);
    }

    /** 当前会话的题目（已有则沿用，没有就生成；题目与答案存会话）。 */
    public String question(SessionAttributes session) {
        return challenge.question(session);
    }

    /** 校验答案并消耗题目（一次一题；答错也要换题）。 */
    public CaptchaChallenge.Result validate(SessionAttributes session, String answer) {
        return challenge.validate(session, answer);
    }

    /** 取当前语言的文案（core 的 MessageSource 已合并插件消息；缺失时显示翻译键本身）。 */
    public String text(String key) {
        return text(key, key);
    }

    /** 取当前语言的文案；{@code fallback} 只在拿不到 MessageSource（独立运行 / 单元测试）或解析抛错时使用。 */
    public String text(String key, String fallback) {
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
