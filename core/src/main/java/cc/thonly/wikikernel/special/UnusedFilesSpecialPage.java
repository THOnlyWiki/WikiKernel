package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.PageLinkService;
import cc.thonly.wikikernel.web.FileTitles;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code 特殊:未使用文件} / {@code Special:UnusedFiles}：没有被任何页面引用的文件。
 */
@Component
public class UnusedFilesSpecialPage extends AbstractReportPage {

    /** 单次检查上限。 */
    private static final int MAX_SCAN = 500;

    private final FileService fileService;
    private final PageLinkService pageLinkService;

    public UnusedFilesSpecialPage(MessageSource messageSource,
                                  FileService fileService,
                                  PageLinkService pageLinkService) {
        super(messageSource);
        this.fileService = fileService;
        this.pageLinkService = pageLinkService;
    }

    @Override
    public String name() {
        return "未使用文件";
    }

    @Override
    public List<String> aliases() {
        return List.of("UnusedFiles");
    }

    @Override
    public String messageKey() {
        return "unusedFiles";
    }

    @Override
    public String category() {
        return SpecialPageCategories.MEDIA;
    }

    @Override
    public String render(SpecialPageRequest request) {
        List<String> items = new ArrayList<>();
        int scanned = 0;
        for (WikiFile file : fileService.all()) {
            if (Boolean.TRUE.equals(file.getDeleted())) {
                continue;
            }
            if (scanned++ >= MAX_SCAN || items.size() >= REPORT_LIMIT) {
                break;
            }
            if (pageLinkService.pagesUsingFile(file.getName(), 1).isEmpty()) {
                items.add(link(FileTitles.titleOf(file.getName()), file.getName()));
            }
        }
        return page(list(items));
    }
}
