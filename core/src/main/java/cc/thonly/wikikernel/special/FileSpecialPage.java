package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.web.FileTitles;
import cc.thonly.wikikernel.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:文件?name=文件名} / {@code Special:File}：旧链接兼容页。
 * <p>
 * 文件存在时 302 跳到文件页「{@code 文件:<名>}」（仿 MediaWiki 的 File 命名空间）；
 * 不存在时提示「文件不存在」。文件页本身由 {@code WikiPageController} 渲染。
 */
@Component
public class FileSpecialPage implements SpecialPage {

    public static final String PARAM_NAME = "name";

    private final FileService fileService;
    private final MessageSource messageSource;

    public FileSpecialPage(FileService fileService, MessageSource messageSource) {
        this.fileService = fileService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "文件";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.file", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("File");
    }

    @Override
    public String template() {
        return "special/file";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        String name = FileTitles.normalizeName(request.parameters().get(PARAM_NAME));
        model.put("lookupName", name);
        if (name.isEmpty()) {
            model.put("nameRequired", true);
            return;
        }
        if (fileService.findAnyByName(name).isPresent()) {
            model.put(REDIRECT_MODEL_ATTRIBUTE, WikiUrls.pagePath(FileTitles.titleOf(name)));
            return;
        }
        model.put("fileNotFound", true);
    }
}
