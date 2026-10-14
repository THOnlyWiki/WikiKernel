package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.service.PagePropertyStore;
import cc.thonly.wikikernel.common.service.PagePropertyValue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 页面属性存储与持久化：
 * 渲染产出属性随页面保存被覆盖；通过 {@code registerDurable} 登记的持久属性（浏览计数）不受影响。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class PagePropertyStoreTest {

    @Autowired
    private PagePropertyStore pagePropertyStore;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private UserService userService;

    @Test
    void storesAndAggregatesNumbers() {
        PageHandle page = createPage("属性聚合");
        String counter = "counter-" + System.nanoTime();

        assertEquals(Optional.empty(), pagePropertyStore.get(page.id(), "demo"));
        pagePropertyStore.set(page.id(), "demo", "hello");
        assertEquals(Optional.of("hello"), pagePropertyStore.get(page.id(), "demo"));
        assertEquals("hello",
                pagePropertyStore.batch(List.of(page.id()), List.of("demo")).get(page.id()).get("demo"));

        assertEquals(2, pagePropertyStore.addToInt(page.id(), counter, 2));
        assertEquals(7, pagePropertyStore.addToInt(page.id(), counter, 5));
        assertEquals(7, pagePropertyStore.sumInt(counter));
        assertEquals(List.of(new PagePropertyValue(page.id(), 7)), pagePropertyStore.listByIntDesc(counter, 10));
    }

    @Test
    void durablePropertySurvivesPageEditWhileRenderedPropertiesAreReplaced() {
        PageHandle page = createPage("属性持久化");
        String durable = "views-" + System.nanoTime();
        String rendered = "stale-" + System.nanoTime();

        pagePropertyStore.registerDurable(durable);
        pagePropertyStore.addToInt(page.id(), durable, 3);
        pagePropertyStore.set(page.id(), rendered, "旧渲染属性");

        // 编辑页面 → PagePropertyEventListener 重新渲染（测试环境没有插件 → 渲染产出为空）
        WikiUser editor = userService.createUser("属性编辑者" + System.nanoTime(), "x12345678", "");
        wikiPageService.editPage(editor, page.title(), "新内容");

        assertEquals(Optional.of("3"), pagePropertyStore.get(page.id(), durable), "持久属性不应被清掉");
        assertFalse(pagePropertyStore.get(page.id(), rendered).isPresent(), "渲染产出属性应被覆盖清除");
    }

    @Test
    void pageDeleteRemovesAllProperties() {
        PageHandle page = createPage("属性删除");
        pagePropertyStore.set(page.id(), "demo", "1");

        WikiUser editor = userService.createUser("属性删除者" + System.nanoTime(), "x12345678", "");
        wikiPageService.deletePage(editor, page.title());

        assertFalse(pagePropertyStore.get(page.id(), "demo").isPresent());
    }

    // ==================== 工具 ====================

    private record PageHandle(long id, String title) {
    }

    private PageHandle createPage(String prefix) {
        String username = prefix + System.nanoTime();
        WikiUser user = userService.createUser(username, "x12345678", "");
        return new PageHandle(wikiPageService.createPage(user, username, "初始内容").getId(), username);
    }
}
