package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.service.LogWriter;
import cc.thonly.wikikernel.service.LogService;
import org.springframework.stereotype.Service;

/**
 * 操作日志写入桥：插件写日志用（类型 id 由 {@code common.extension.LogType} 注册中心解析）。
 */
@Service
public class LogWriterImpl implements LogWriter {

    private final LogService logService;

    public LogWriterImpl(LogService logService) {
        this.logService = logService;
    }

    @Override
    public void record(String logType, Long actorUserId, String target, Long targetUserId, String detail) {
        logService.record(logType, actorUserId, target, targetUserId, detail);
    }
}
