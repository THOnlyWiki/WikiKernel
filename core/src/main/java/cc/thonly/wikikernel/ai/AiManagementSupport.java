package cc.thonly.wikikernel.ai;

import cc.thonly.wikikernel.common.extension.Viewer;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AiManagementSupport {
    private final MessageSource messages;

    public AiManagementSupport(MessageSource messages) {
        this.messages = messages;
    }

    public static boolean isManager(Viewer viewer) {
        return viewer != null && viewer.loggedIn()
                && (viewer.inGroup("管理员") || viewer.inGroup("行政员"));
    }

    public String message(String key, String fallback, Object... args) {
        return messages.getMessage(key, args, fallback, LocaleContextHolder.getLocale());
    }
}
