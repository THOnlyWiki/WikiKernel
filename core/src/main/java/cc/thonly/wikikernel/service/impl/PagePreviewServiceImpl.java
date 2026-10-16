package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.PagePreviewService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.wikitext.WikiSections;
import cc.thonly.wikikernel.wikitext.WikitextRenderer;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link PagePreviewService} 实现：
 * 取首段（{@link WikiSections#extract}）→ 渲染成 HTML → 去标记得到纯文本 → 截断；
 * 缩略图取首段里第一张嵌入的图片（没有就看全文）。
 */
@Service
public class PagePreviewServiceImpl implements PagePreviewService {

    /** 文件嵌入：{@code [[文件:名称|…]]} / {@code [[File:…]]} / {@code [[Image:…]]}。 */
    private static final Pattern FILE_EMBED = Pattern.compile(
            "\\[\\[(?:文件|File|Image|图像|圖片)\\s*:\\s*([^\\[\\]|#]+)", Pattern.CASE_INSENSITIVE);

    /** 文件嵌入（整段，用于从摘要里去掉）：{@code [[文件:…|…]]}。 */
    private static final Pattern FILE_EMBED_FULL = Pattern.compile(
            "\\[\\[(?:文件|File|Image|图像|圖片)\\s*:[^\\[\\]]*\\]\\]", Pattern.CASE_INSENSITIVE);

    /** 重定向：{@code #REDIRECT [[目标]]} / {@code #重定向 [[目标]]}。 */
    private static final Pattern REDIRECT = Pattern.compile(
            "^\\s*#(?:REDIRECT|重定向|重新導向|転送)\\s*:?\\s*\\[\\[([^\\[\\]|#]+)", Pattern.CASE_INSENSITIVE);

    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;
    private final WikitextRenderer wikitextRenderer;
    private final FileService fileService;

    public PagePreviewServiceImpl(WikiPageService wikiPageService,
                                  AccessControlService accessControlService,
                                  WikitextRenderer wikitextRenderer,
                                  FileService fileService) {
        this.wikiPageService = wikiPageService;
        this.accessControlService = accessControlService;
        this.wikitextRenderer = wikitextRenderer;
        this.fileService = fileService;
    }

    @Override
    public Optional<Preview> preview(String title, WikiUser viewer) {
        return preview(WikiTitles.normalize(title), viewer, 0);
    }

    private Optional<Preview> preview(String title, WikiUser viewer, int depth) {
        if (title.isEmpty() || depth > 1 || isSpecial(title)) {
            return Optional.empty();
        }
        Optional<WikiPage> pageOpt = wikiPageService.getVisibleByTitle(title);
        if (pageOpt.isEmpty()) {
            return Optional.empty();
        }
        WikiPage page = pageOpt.get();
        if (!accessControlService.canRead(page.getTitle(), viewer)) {
            return Optional.empty();
        }
        String content = wikiPageService.getLatestContent(page.getId()).orElse("");
        // 重定向：跟随一次（预览目标的内容，标题仍显示原链接的标题）
        Matcher redirect = REDIRECT.matcher(content);
        if (redirect.find()) {
            Optional<Preview> target = preview(WikiTitles.normalize(redirect.group(1)), viewer, depth + 1);
            if (target.isPresent()) {
                Preview preview = target.get();
                return Optional.of(new Preview(page.getTitle(), WikiUrls.pagePath(page.getTitle()),
                        preview.extract(), preview.thumbnailUrl(), preview.thumbnailAlt()));
            }
        }
        String lead = Strings.trimToEmpty(WikiSections.extract(content, 0));
        if (lead.isEmpty()) {
            lead = content; // 没有段落结构时用全文
        }
        // 摘要：去掉文件嵌入（缩略图单独展示），再渲染成纯文本
        String forExtract = FILE_EMBED_FULL.matcher(lead).replaceAll(" ");
        String plain = toPlainText(wikitextRenderer.render(forExtract, page.getTitle()));
        String extract = truncate(plain, EXTRACT_LIMIT);
        Thumbnail thumbnail = thumbnail(lead, content);
        return Optional.of(new Preview(page.getTitle(), WikiUrls.pagePath(page.getTitle()),
                extract, thumbnail.url, thumbnail.alt));
    }

    /** 特殊页没有页面行，不做预览。 */
    private static boolean isSpecial(String title) {
        return title.regionMatches(true, 0, "特殊:", 0, 3)
                || title.regionMatches(true, 0, "Special:", 0, 8);
    }

    /** 渲染后的 HTML → 纯文本（去脚本 / 样式 / 标签、解实体、压缩空白）。 */
    static String toPlainText(String html) {
        String text = html
                .replaceAll("(?s)<script.*?</script>", " ")
                .replaceAll("(?s)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ");
        text = HtmlUtils.htmlUnescape(text);
        return text.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    /** 截断到 limit 字以内，尽量在标点 / 空格处断开，末尾加省略号。 */
    static String truncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int cut = -1;
        for (int i = Math.min(limit, text.length() - 1); i > limit / 2; i--) {
            char c = text.charAt(i);
            if ("。！？；，、.!?;, ".indexOf(c) >= 0) {
                cut = i;
                break;
            }
        }
        if (cut <= 0) {
            cut = limit;
        }
        return text.substring(0, cut + 1).trim() + "…";
    }

    /** 缩略图：优先首段里的第一张图片，其次全文里的第一张。 */
    private Thumbnail thumbnail(String lead, String content) {
        for (String source : new String[]{lead, content}) {
            Matcher matcher = FILE_EMBED.matcher(source);
            while (matcher.find()) {
                String name = matcher.group(1).trim().replace('_', ' ');
                Optional<WikiFile> file = fileService.findByName(name);
                if (file.isPresent() && fileService.isImage(file.get())) {
                    return new Thumbnail(fileService.urlOf(file.get()), file.get().getName());
                }
            }
        }
        return new Thumbnail(null, null);
    }

    private record Thumbnail(String url, String alt) {
    }
}
