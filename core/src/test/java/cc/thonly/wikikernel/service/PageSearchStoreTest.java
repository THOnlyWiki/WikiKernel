package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.PageSearchStore;
import cc.thonly.wikikernel.common.service.PageSearchStore.PageQuery;
import cc.thonly.wikikernel.common.service.PageSearchStore.PageRow;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DPL 用页面检索：分类 / 命名空间 / 标题过滤与排序。 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class PageSearchStoreTest {

    @Autowired
    private PageSearchStore pageSearchStore;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private PageCategoryService pageCategoryService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void filtersByCategoryNamespaceAndTitle() {
        WikiUser user = user();
        String suffix = String.valueOf(System.nanoTime());
        String category = "DPL检索测试" + suffix;
        String first = "DPLA" + suffix;
        String second = "DPLB" + suffix;
        String otherNamespace = "模板:DPL模板" + suffix;
        wikiPageService.editPage(user, first, "正文 [[分类:" + category + "]]", "创建");
        wikiPageService.editPage(user, second, "正文 [[分类:" + category + "]]", "创建");
        wikiPageService.editPage(user, otherNamespace, "模板正文 [[分类:" + category + "]]", "创建");
        pageCategoryService.reindex(first);
        pageCategoryService.reindex(second);
        pageCategoryService.reindex(otherNamespace);

        List<PageRow> main = pageSearchStore.query(new PageQuery(
                "main", List.of(category), List.of(), "", "", "", "",
                "title", false, 0, 10, "include"));
        assertEquals(List.of(first, second), main.stream().map(PageRow::title).toList(),
                "主命名空间内按标题升序，且模板页不出现");

        List<PageRow> all = pageSearchStore.query(new PageQuery(
                "all", List.of(category), List.of(), "", "", "", "",
                "title", false, 0, 10, "include"));
        assertTrue(all.stream().map(PageRow::title).anyMatch(otherNamespace::equals),
                "命名空间 all 应包含模板页");

        List<PageRow> regex = pageSearchStore.query(new PageQuery(
                "all", List.of(category), List.of(), "", "", "DPLA", "",
                "title", false, 0, 10, "include"));
        assertEquals(List.of(first), regex.stream().map(PageRow::title).toList());

        List<PageRow> excluded = pageSearchStore.query(new PageQuery(
                "main", List.of(category), List.of(category), "", "", "", "",
                "title", false, 0, 10, "include"));
        assertTrue(excluded.isEmpty(), "notcategory 与 category 相同时应无结果");
    }

    @Test
    void respectsOffsetAndCount() {
        WikiUser user = user();
        String suffix = String.valueOf(System.nanoTime());
        String category = "DPL分页测试" + suffix;
        for (int i = 0; i < 5; i++) {
            String title = "DPL分页" + i + suffix;
            wikiPageService.editPage(user, title, "正文 [[分类:" + category + "]]", "创建");
            pageCategoryService.reindex(title);
        }
        List<PageRow> page = pageSearchStore.query(new PageQuery(
                "main", List.of(category), List.of(), "", "", "", "",
                "title", false, 2, 2, "include"));
        assertEquals(2, page.size());
        assertFalse(page.get(0).title().equals(page.get(1).title()));
    }

    private WikiUser user() {
        WikiUser user = new WikiUser();
        user.setUsername("DPL检索用户" + System.nanoTime());
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }
}
