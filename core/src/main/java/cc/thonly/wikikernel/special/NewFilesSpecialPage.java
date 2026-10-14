package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.time.WikiTime;
import cc.thonly.wikikernel.web.FileTitles;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:最新文件} / {@code Special:NewFiles}：最近上传的文件（新的在前）。
 */
@Component
public class NewFilesSpecialPage extends AbstractReportPage {

    private final FileService fileService;
    private final UserService userService;
    private final WikiTime wikiTime;

    public NewFilesSpecialPage(MessageSource messageSource,
                               FileService fileService,
                               UserService userService,
                               WikiTime wikiTime) {
        super(messageSource);
        this.fileService = fileService;
        this.userService = userService;
        this.wikiTime = wikiTime;
    }

    @Override
    public String name() {
        return "最新文件";
    }

    @Override
    public List<String> aliases() {
        return List.of("NewFiles", "NewImages");
    }

    @Override
    public String messageKey() {
        return "newFiles";
    }

    @Override
    public String category() {
        return SpecialPageCategories.MEDIA;
    }

    @Override
    public String render(SpecialPageRequest request) {
        List<WikiFile> files = new ArrayList<>();
        for (WikiFile file : fileService.all()) {
            if (!Boolean.TRUE.equals(file.getDeleted())) {
                files.add(file);
            }
        }
        files.sort(Comparator.comparing(WikiFile::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (files.size() > REPORT_LIMIT) {
            files = files.subList(0, REPORT_LIMIT);
        }
        Map<Long, String> names = userService.usernames(files.stream()
                .map(WikiFile::getUploaderId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList());
        List<List<String>> rows = new ArrayList<>();
        for (WikiFile file : files) {
            String uploader = file.getUploaderId() == null
                    ? message("pageInfo.anonymous")
                    : names.getOrDefault(file.getUploaderId(), message("pageInfo.anonymous"));
            rows.add(List.of(
                    link(FileTitles.titleOf(file.getName()), file.getName()),
                    escape(uploader),
                    escape(wikiTime.format(file.getCreatedAt()))));
        }
        return page(table(List.of(message("report.col.page"), message("report.col.user"), message("report.col.time")), rows));
    }
}
