package cc.thonly.wikikernel.time;

import cc.thonly.wikikernel.common.service.TimeDisplay;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 与页面历史、日志相同的请求时区换算，供独立插件调用。 */
@Component
public class WikiTimeDisplay implements TimeDisplay {
    @Override
    public String format(LocalDateTime time, String pattern) {
        return WikiTime.format(time, pattern);
    }
}
