package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.FileService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code 特殊:媒体统计} / {@code Special:MediaStatistics}：按 MIME 类型统计文件数量与总体积。
 */
@Component
public class MediaStatisticsSpecialPage extends AbstractReportPage {

    private final FileService fileService;

    public MediaStatisticsSpecialPage(MessageSource messageSource, FileService fileService) {
        super(messageSource);
        this.fileService = fileService;
    }

    @Override
    public String name() {
        return "媒体统计";
    }

    @Override
    public List<String> aliases() {
        return List.of("MediaStatistics");
    }

    @Override
    protected String messageKey() {
        return "mediaStatistics";
    }

    @Override
    public String category() {
        return SpecialPageCategories.MEDIA;
    }

    @Override
    public String render(SpecialPageRequest request) {
        Map<String, long[]> byType = new LinkedHashMap<>();
        for (WikiFile file : fileService.all()) {
            if (Boolean.TRUE.equals(file.getDeleted())) {
                continue;
            }
            String type = file.getContentType() == null || file.getContentType().isBlank()
                    ? "application/octet-stream" : file.getContentType();
            long[] stats = byType.computeIfAbsent(type, key -> new long[2]);
            stats[0]++;
            stats[1] += file.getSize() == null ? 0L : file.getSize();
        }
        List<Map.Entry<String, long[]>> entries = new ArrayList<>(byType.entrySet());
        entries.sort(Comparator.<Map.Entry<String, long[]>, Long>comparing(entry -> entry.getValue()[0]).reversed()
                .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : entries) {
            if (rows.size() >= REPORT_LIMIT) {
                break;
            }
            rows.add(List.of(escape(entry.getKey()), String.valueOf(entry.getValue()[0]),
                    escape(formatBytes(entry.getValue()[1]))));
        }
        return page(table(List.of(message("report.col.mime"), message("report.col.files"),
                message("report.col.size")), rows));
    }

    /** 人类可读体积（B / KB / MB / GB）。 */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
