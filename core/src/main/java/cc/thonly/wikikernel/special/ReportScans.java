package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.web.FileTitles;
import cc.thonly.wikikernel.web.WikiTitles;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 报告类特殊页的正文扫描辅助（仿 MediaWiki 的 linktables 维护报告）：
 * 从「最新版本正文」里提取页面链接与文件嵌入，供最多链接页面 / 待撰文件 / 断链页面等报告复用。
 */
final class ReportScans {

    /** {@code [[目标]] / [[目标|文字]] / [[目标#锚点]]}。 */
    private static final Pattern LINK = Pattern.compile("\\[\\[\\s*([^\\[\\]|#<>]+)");

    /** {@code [[文件:名称.png…]]} / {@code [[File:名称.png…]]} / {@code [[Image:名称.png…]]}。 */
    private static final Pattern FILE_LINK = Pattern.compile(
            "\\[\\[\\s*(?:文件|File|Image)\\s*:\\s*([^\\[\\]|#<>]+)", Pattern.CASE_INSENSITIVE);

    private ReportScans() {
    }

    /** 正文里的页面链接目标（已规范化；分类 / 文件 / 特殊页 / 外部链接不算页面链接）。 */
    static List<String> pageLinks(String content) {
        List<String> targets = new ArrayList<>();
        Matcher matcher = LINK.matcher(content == null ? "" : content);
        while (matcher.find()) {
            String target = WikiTitles.normalize(matcher.group(1));
            if (!target.isEmpty() && !skipped(target)) {
                targets.add(target);
            }
        }
        return targets;
    }

    /** 正文里嵌入的文件名（已规范化，如 {@code 示例.png}）。 */
    static List<String> fileNames(String content) {
        List<String> names = new ArrayList<>();
        Matcher matcher = FILE_LINK.matcher(content == null ? "" : content);
        while (matcher.find()) {
            String name = FileTitles.normalizeName(matcher.group(1));
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 读取某个（可含英文别名的）命名空间下的可见页面，按标题排序。
     * 索引在数据库层就按前缀过滤，避免主命名空间页面把扫描窗口挤满（模板 / 分类命名空间报告用）。
     */
    static List<WikiPage> namespacePages(WikiPageMapper wikiPageMapper, List<String> prefixes, int limit) {
        Map<Long, WikiPage> byId = new LinkedHashMap<>();
        for (String prefix : prefixes) {
            for (WikiPage page : wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                    .likeRight(WikiPage::getTitle, prefix)
                    .eq(WikiPage::getDeleted, false)
                    .eq(WikiPage::getHided, false)
                    .orderByAsc(WikiPage::getTitle)
                    .last("LIMIT " + limit))) {
                byId.putIfAbsent(page.getId(), page);
            }
        }
        List<WikiPage> pages = new ArrayList<>(byId.values());
        pages.sort((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.getTitle(), right.getTitle()));
        return pages;
    }

    /** 跳过分类 / 文件 / 特殊页与外部链接（它们不算普通页面链接）。 */
    private static boolean skipped(String target) {
        String lower = target.toLowerCase(Locale.ROOT);
        return lower.startsWith("分类:") || lower.startsWith("category:")
                || lower.startsWith("文件:") || lower.startsWith("file:")
                || lower.startsWith("特殊:") || lower.startsWith("special:")
                || lower.startsWith("http://") || lower.startsWith("https://");
    }
}
