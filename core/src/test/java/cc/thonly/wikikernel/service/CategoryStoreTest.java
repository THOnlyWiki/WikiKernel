package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.CategoryCounts;
import cc.thonly.wikikernel.common.service.CategoryMember;
import cc.thonly.wikikernel.common.service.CategoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分类索引桥接（CategoryStore）：分类成员 / 所属分类 / 成员统计（CategoryTree 等插件依赖）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class CategoryStoreTest {

    @Autowired
    private CategoryStore categoryStore;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private UserService userService;

    @Test
    void membersCategoriesOfAndCounts() {
        String suffix = String.valueOf(System.nanoTime());
        String categoryName = "树测试" + suffix;
        WikiUser user = userService.createUser("分类树用户" + suffix, "x12345678", "");

        wikiPageService.createPage(user, "分类树页面A" + suffix, "正文 [[分类:" + categoryName + "]]");
        wikiPageService.createPage(user, "分类树页面B" + suffix, "正文 [[分类:" + categoryName + "|排序键]]");
        wikiPageService.createPage(user, "分类:子树测试" + suffix, "[[分类:" + categoryName + "]]");

        // 分类成员：两个内容页 + 一个子分类
        List<CategoryMember> members = categoryStore.members(categoryName, 0);
        Set<String> titles = members.stream().map(CategoryMember::title).collect(Collectors.toSet());
        assertEquals(Set.of("分类树页面A" + suffix, "分类树页面B" + suffix, "分类:子树测试" + suffix), titles);
        assertTrue(members.stream().anyMatch(member -> member.title().startsWith("分类:") && member.subcategory()));
        assertTrue(members.stream().anyMatch(member -> member.title().startsWith("分类树页面A") && member.contentPage()));

        // 中英文前缀与 limit 参数
        assertEquals(2, categoryStore.members("分类:" + categoryName, 2).size());
        assertEquals(3, categoryStore.members("Category:" + categoryName, 10).size());

        // 成员统计：1 个子分类 + 2 个内容页面
        CategoryCounts counts = categoryStore.counts(categoryName);
        assertEquals(1, counts.subcategories());
        assertEquals(2, counts.pages());
        assertEquals(0, counts.files());
        assertEquals(3, counts.all());

        // 子分类页面所属分类
        long subcategoryPageId = wikiPageService.getByTitle("分类:子树测试" + suffix).orElseThrow().getId();
        assertTrue(categoryStore.categoriesOf(subcategoryPageId).contains("分类:" + categoryName));
    }
}
