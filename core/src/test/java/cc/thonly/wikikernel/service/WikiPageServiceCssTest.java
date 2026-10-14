package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link WikiPageService#getCSS(String)}：
 * 全站 {@code Wiki.css} + 当前页面的 {@code {title}/XXX.css} 子页面（取最新可见版本内容），
 * 过滤隐藏/删除页面、已删除版本与非 CSS 子页面。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class WikiPageServiceCssTest {

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiRevisionMapper wikiRevisionMapper;

    @Test
    void collectsSiteCssThenPageStyles() {
        insertPage("Wiki.css", "body { color: red; }");
        insertPage("WikiKernel:Common.css", ".common {}");
        insertPage("CSS测试页/样式B.css", ".b {}");
        insertPage("CSS测试页/样式A.css", ".a {}");
        insertPage("CSS测试页/说明.txt", "不是 CSS");

        List<String> styles = wikiPageService.getCSS("CSS测试页");

        // 全站样式排最前（Wiki.css → WikiKernel:Common.css），子页面按标题排序
        assertEquals(List.of("body { color: red; }", ".common {}", ".a {}", ".b {}"), styles);
    }

    @Test
    void ignoresHiddenDeletedAndNonCssPages() {
        insertPage("CSS过滤页/可见.css", ".visible {}");
        insertPage("CSS过滤页/说明.txt", "不是 CSS");
        insertPage("CSS过滤页/隐藏.css", ".hidden {}", true, false);
        insertPage("CSS过滤页/删除.css", ".deleted {}", false, true);
        insertPage("CSS过滤页子页/样式.css", ".other {}");
        // 页面可见但最新版本被删除：不应注入
        insertPage("CSS过滤页/版本删除.css", ".revdel {}", false, false, true);

        List<String> styles = wikiPageService.getCSS("CSS过滤页");

        assertTrue(styles.contains(".visible {}"));
        assertFalse(styles.contains("不是 CSS"));
        assertFalse(styles.contains(".hidden {}"));
        assertFalse(styles.contains(".deleted {}"));
        assertFalse(styles.contains(".other {}"), "同前缀父子页面不能误匹配");
        assertFalse(styles.contains(".revdel {}"), "最新版本已删除的页面不应注入样式");
    }

    @Test
    void blankTitleSkipsSubpageQuery() {
        List<String> styles = wikiPageService.getCSS(null);

        // 只可能有全站样式（Wiki.css / WikiKernel:Common.css），不会命中任何子页面
        assertTrue(styles.size() <= 2);
        assertFalse(styles.contains(".a {}"));
        assertFalse(styles.contains(".b {}"));
        assertEquals(styles, wikiPageService.getCSS("   "));
    }

    private void insertPage(String title, String content) {
        insertPage(title, content, false, false, false);
    }

    private void insertPage(String title, String content, boolean hided, boolean deleted) {
        insertPage(title, content, hided, deleted, false);
    }

    private void insertPage(String title, String content, boolean hided, boolean deleted, boolean revisionDeleted) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 12, 0, 0);
        WikiPage page = new WikiPage();
        page.setTitle(title);
        page.setHided(hided);
        page.setDeleted(deleted);
        page.setCreateAt(now);
        page.setUpdateAt(now);
        wikiPageMapper.insert(page);

        WikiRevision revision = new WikiRevision();
        revision.setPageId(page.getId());
        revision.setContent(content);
        revision.setDeleted(revisionDeleted);
        revision.setCreateAt(now);
        wikiRevisionMapper.insert(revision);

        page.setLatestRevisionId(revision.getId());
        wikiPageMapper.updateById(page);
    }
}
