package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.web.CodePages;
import cc.thonly.wikikernel.web.WikiTitles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按皮肤生效的站点样式 / 脚本页（仿 MediaWiki 的 {@code MediaWiki:<皮肤>.css} / {@code .js}）：
 * <ul>
 *     <li>注入顺序：站点 → 通用 → <b>皮肤</b> → 页面级子页面（皮肤最靠后，便于覆盖前面的规则）；</li>
 *     <li>皮肤页不存在 / 被隐藏 / 最新版本被删除 / 皮肤 id 非法时**跳过、不报错**；</li>
 *     <li>皮肤只按 <b>id</b> 匹配（{@code plain} → {@code WikiKernel:plain.css}），标题仍走标题规范化
 *     （首字母自动大写，所以 {@code WikiKernel:plain.css} 与 {@code WikiKernel:Plain.css} 是同一个页面）；</li>
 *     <li>不同皮肤各自取自己的页面（同一份内容不会串到别的皮肤）。</li>
 * </ul>
 * 本类用独立 Spring 上下文（多一个 {@code wikikernel.skins.default-skin} 属性），不污染其它测试的内存库；
 * 类内方法共享同一个库，所以除 {@link #skinCssSitsAfterCommonCss()} / {@link #skinJsSitsAfterCommonJs()}
 * 之外的用例都不断言「全站条目」——只断言「允许出现的条目集合」是否恰好命中本用例自己的皮肤页。
 */
@SpringBootTest(properties = {
        "wikikernel.database.file=:memory:",
        "wikikernel.skins.default-skin=fallback"
})
class WikiPageServiceSkinTest {

    /** 由 {@link #skinCssSitsAfterCommonCss()} 写入、可能出现在同类其它用例结果里的全站样式。 */
    private static final List<String> SITE_WIDE_CSS =
            List.of("body { color: red; }", ".common {}");

    /** 由 {@link #skinJsSitsAfterCommonJs()} 写入、可能出现在同类其它用例结果里的全站脚本标题。 */
    private static final String COMMON_JS_TITLE = "WikiKernel:Common.js";

    /** 本类用例可能写入的全站样式（Wiki.css / WikiKernel:Common.css）内容。 */
    private static final String COMMON_CSS = ".common {}";

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiRevisionMapper wikiRevisionMapper;

    @Test
    void skinCssSitsAfterCommonCss() {
        insertPage("Wiki.css", "body { color: red; }");
        insertPage("WikiKernel:Common.css", COMMON_CSS);
        insertPage("WikiKernel:skintest.css", ".skin {}");
        insertPage("皮肤顺序页/A.css", ".page-a {}");
        insertPage("皮肤顺序页/B.css", ".page-b {}");

        List<String> styles = wikiPageService.getCSS("皮肤顺序页", "skintest");

        // 站点 → 通用 → 皮肤 → 页面级（页面级按标题排序）：皮肤排在 Common 之后、页面级之前
        assertEquals(List.of("body { color: red; }", COMMON_CSS, ".skin {}", ".page-a {}", ".page-b {}"), styles);
    }

    @Test
    void skinJsSitsAfterCommonJs() {
        insertPage("WikiKernel:Common.js", "window.WIKI_COMMON_JS = true;");
        insertPage("WikiKernel:skintest.js", "window.__skinJsLoaded = true;");
        insertPage("皮肤脚本页/甲.js", "window.pageA = true;");

        List<String> scripts = wikiPageService.getJS("皮肤脚本页", "skintest");

        // 返回的是页面规范标题（首字母已大写）
        assertEquals(List.of(COMMON_JS_TITLE, "WikiKernel:Skintest.js", "皮肤脚本页/甲.js"), scripts);
    }

    @Test
    void missingSkinPageIsSkippedQuietly() {
        insertPage("WikiKernel:present.css", ".present {}");
        insertPage("WikiKernel:present.js", "window.present = true;");
        insertPage("皮肤缺省页/甲.css", ".sole-page {}");
        insertPage("皮肤缺省页/甲.js", "window.solePage = true;");

        // 皮肤页不存在（以及空白 / 非法字符的皮肤 id）：只注入页面级条目，不报错
        for (String skinId : new String[]{null, "", "   ", "nosuchskin", "a/b", "skin.test"}) {
            assertEquals(List.of(".sole-page {}"), withoutCommonCss(
                    wikiPageService.getCSS("皮肤缺省页", skinId)),
                    "皮肤 id=" + skinId + " 不应注入站点样式");
            assertEquals(List.of("皮肤缺省页/甲.js"), withoutCommonJs(
                    wikiPageService.getJS("皮肤缺省页", skinId)),
                    "皮肤 id=" + skinId + " 不应注入站点脚本");
        }

        // 相邻皮肤（present vs present2）不会互相误命中
        List<String> styles = wikiPageService.getCSS("皮肤缺省页", "present2");
        assertFalse(styles.contains(".present {}"), styles.toString());
    }

    @Test
    void hiddenOrDeletedSkinPageIsNotInjected() {
        insertPage("WikiKernel:hidden.css", ".hidden {}", true, false);
        insertPage("WikiKernel:deleted.css", ".deleted {}", false, true);
        insertPage("WikiKernel:revdel.css", ".revdel {}", false, false, true);
        insertPage("WikiKernel:hidden.js", "window.hidden = true;", true, false);
        insertPage("WikiKernel:deleted.js", "window.deleted = true;", false, true);
        insertPage("皮肤过滤页/甲.css", ".filtered-page {}");
        insertPage("皮肤过滤页/甲.js", "window.filteredPage = true;");

        for (String skinId : new String[]{"hidden", "deleted", "revdel"}) {
            assertEquals(List.of(".filtered-page {}"), withoutCommonCss(
                    wikiPageService.getCSS("皮肤过滤页", skinId)), "被隐藏 / 删除的皮肤样式不应注入");
            assertEquals(List.of("皮肤过滤页/甲.js"), withoutCommonJs(
                    wikiPageService.getJS("皮肤过滤页", skinId)), "被隐藏 / 删除的皮肤脚本不应注入");
        }
    }

    @Test
    void twoSkinsInjectTheirOwnPages() {
        insertPage("WikiKernel:skina.css", ".skina {}");
        insertPage("WikiKernel:skinb.css", ".skinb {}");
        insertPage("WikiKernel:skina.js", "window.skinA = true;");
        insertPage("WikiKernel:skinb.js", "window.skinB = true;");
        insertPage("皮肤切换页/甲.css", ".switch-page {}");
        insertPage("皮肤切换页/甲.js", "window.switchPage = true;");

        // 同一页面、不同皮肤：各自注入自己皮肤的条目，且不含对方的页面
        assertEquals(List.of(".skina {}", ".switch-page {}"),
                withoutCommonCss(wikiPageService.getCSS("皮肤切换页", "skina")));
        assertEquals(List.of(".skinb {}", ".switch-page {}"),
                withoutCommonCss(wikiPageService.getCSS("皮肤切换页", "skinb")));
        assertEquals(List.of("WikiKernel:Skina.js", "皮肤切换页/甲.js"),
                withoutCommonJs(wikiPageService.getJS("皮肤切换页", "skina")));
        assertEquals(List.of("WikiKernel:Skinb.js", "皮肤切换页/甲.js"),
                withoutCommonJs(wikiPageService.getJS("皮肤切换页", "skinb")));
    }

    @Test
    void skinIdMatchingFollowsTitleNormalizationRules() {
        String page = "皮肤大小写页" + System.nanoTime();
        insertPage("WikiKernel:plaincase.css", ".plaincase {}");
        insertPage("WikiKernel:PLAINUP.css", ".plainup {}");
        insertPage(page + "/甲.css", ".case-page {}");

        // 皮肤 id 原样拼成 WikiKernel:<id>.css，再走标题规范化：首字母自动大写 ⇒ plaincase / Plaincase 命中同一页
        assertEquals(List.of(".plaincase {}", ".case-page {}"),
                withoutCommonCss(wikiPageService.getCSS(page, "plaincase")));
        assertEquals(List.of(".plaincase {}", ".case-page {}"),
                withoutCommonCss(wikiPageService.getCSS(page, "Plaincase")));
        // 首字母之后区分大小写：PLAINUP 命中 WikiKernel:PLAINUP.css，plainup 谁也不命中
        assertEquals(List.of(".plainup {}", ".case-page {}"),
                withoutCommonCss(wikiPageService.getCSS(page, "PLAINUP")));
        assertEquals(List.of(".case-page {}"),
                withoutCommonCss(wikiPageService.getCSS(page, "plainup")));
    }

    @Test
    void skinPagesAreCodePages() {
        // 皮肤页与 WikiKernel:Common.css/.js 一样属于「代码页面」（CSS / JS 页判定，界面管理员才可编辑）
        assertTrue(CodePages.isInterfacePage("WikiKernel:plain.css"));
        assertTrue(CodePages.isInterfacePage("WikiKernel:plain.js"));
        assertTrue(CodePages.isInterfacePage("WikiKernel:Plain.CSS"), "后缀判定大小写不敏感");
    }

    @Test
    void pageSubpagesStillComeLastWithoutSkinPage() {
        insertPage("皮肤回归页/甲.css", ".regress {}");

        // 没有皮肤页时行为与加皮肤页之前一致：页面级条目照旧（外加可能由同类其它用例写入的全站条目）
        assertEquals(List.of(".regress {}"),
                withoutCommonCss(wikiPageService.getCSS("皮肤回归页", "regressskin")));
    }

    /**
     * 去掉可能由同类其它用例写入的全站条目（共享内存库，顺序无关地断言）：
     * {@code Wiki.css} 与 {@code WikiKernel:Common.css} 是全站生效的，本类只有
     * {@link #skinCssSitsAfterCommonCss()} 写入它们，断言皮肤页时先滤掉。
     */
    private static List<String> withoutCommonCss(List<String> styles) {
        List<String> result = new ArrayList<>(styles);
        result.removeAll(SITE_WIDE_CSS);
        return result;
    }

    /** 去掉可能由同类其它用例写入的全站 Common 脚本标题。 */
    private static List<String> withoutCommonJs(List<String> scripts) {
        List<String> result = new ArrayList<>(scripts);
        result.remove(COMMON_JS_TITLE);
        return result;
    }

    private void insertPage(String title, String content) {
        insertPage(title, content, false, false, false);
    }

    private void insertPage(String title, String content, boolean hided, boolean deleted) {
        insertPage(title, content, hided, deleted, false);
    }

    /** 直接落库（标题按应用规则规范化，模拟真实保存后的行）。 */
    private void insertPage(String title, String content, boolean hided, boolean deleted, boolean revisionDeleted) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 6, 12, 0, 0);
        WikiPage page = new WikiPage();
        page.setTitle(WikiTitles.normalize(title));
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
