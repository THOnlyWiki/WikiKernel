package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.wikitext.WikitextRenderer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 系统界面配置页：WikiKernel 优先，兼容导入的 MediaWiki 同名页。 */
@Service
public class SystemInterfaceService {
    private final WikiPageService pages;
    private final AccessControlService access;
    private final WikitextRenderer renderer;

    public SystemInterfaceService(WikiPageService pages, AccessControlService access, WikitextRenderer renderer) {
        this.pages = pages;
        this.access = access;
        this.renderer = renderer;
    }

    /** 空白与单独的 '-' 表示关闭；只使用当前用户可读、可见且版本未隐藏的配置。 */
    public String render(String key, Locale locale, WikiUser viewer) {
        for (String title : candidates(key, locale)) {
            if (!access.canRead(title, viewer)) {
                continue;
            }
            var page = pages.getVisibleByTitle(title);
            if (page.isEmpty()) {
                continue;
            }
            var content = pages.getLatestContent(page.get().getId());
            if (content.isEmpty()) {
                continue;
            }
            String source = content.get();
            if (source.isBlank() || "-".equals(source.trim())) {
                return null;
            }
            return renderer.renderWithMeta(source, title).html();
        }
        return null;
    }

    private static List<String> candidates(String key, Locale locale) {
        List<String> titles = new ArrayList<>();
        Locale language = locale == null ? Locale.ROOT : locale;
        for (String namespace : List.of("WikiKernel:", "MediaWiki:")) {
            String tag = language.toLanguageTag().toLowerCase(Locale.ROOT);
            if (!language.getLanguage().isEmpty()) {
                titles.add(namespace + key + "/" + tag);
                if (!tag.equals(language.getLanguage())) {
                    titles.add(namespace + key + "/" + language.getLanguage());
                }
            }
            titles.add(namespace + key);
        }
        return titles;
    }
}
