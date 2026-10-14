package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.db.mapper.WikiUserMapper;
import cc.thonly.wikikernel.service.WikiPageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分类（{@code 分类:X}）与模板反查：
 * 分类链接从正文剥离并在底部显示；分类页列出成员（排序键）；模板里的分类同样生效；
 * {@code Category:X} 别名 302；{@code [[:分类:X]]} 只是普通链接；
 * 模板使用支持反查（链入页面）与正查（页面信息）。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
@AutoConfigureMockMvc
class CategoryTemplateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private WikiUserMapper wikiUserMapper;

    @Test
    void categoryLinksAreStrippedAndCategoryPageListsMembersWithSortKeys() throws Exception {
        WikiUser user = insertUser("分类用户" + System.nanoTime());
        String unique = String.valueOf(System.nanoTime());
        String dessert = "甜点" + unique;
        String apple = "苹果派" + unique;
        String brownie = "布朗尼" + unique;
        String cake = "蛋糕" + unique;
        wikiPageService.editPage(user, apple, "苹果派正文 [[分类:" + dessert + "]]");
        wikiPageService.editPage(user, brownie, "布朗尼正文 [[分类:" + dessert + "|B]]");
        wikiPageService.editPage(user, cake, "蛋糕正文 [[分类:" + dessert + "|A]] [[分类:烘焙" + unique + "]]");

        // 正文里分类链接不再内联渲染，底部显示分类栏（外层 .catlinks-block 撑开贴底）
        MvcResult appleResult = mockMvc.perform(get("/wiki/{title}", apple))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("苹果派正文")))
                .andExpect(content().string(containsString("class=\"catlinks\"")))
                .andExpect(content().string(containsString("class=\"catlinks-block\"")))
                .andExpect(content().string(containsString("catlinks-spacer")))
                .andExpect(content().string(containsString(dessert)))
                .andReturn();
        String appleHtml = appleResult.getResponse().getContentAsString();
        assertTrue(appleHtml.indexOf("苹果派正文") < appleHtml.indexOf("catlinks-spacer"),
                "分类栏应排在正文之后（贴内容容器底部）");

        // 分类页：成员按排序键（A、B、标题）排序，并显示排序键
        MvcResult result = mockMvc.perform(get("/wiki/{title}", "分类:" + dessert))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(cake)))
                .andExpect(content().string(containsString(brownie)))
                .andExpect(content().string(containsString(apple)))
                .andExpect(content().string(containsString("此分类还没有说明")))
                .andExpect(content().string(containsString("[A]")))
                .andExpect(content().string(containsString("[B]")))
                .andReturn();
        String html = result.getResponse().getContentAsString();
        assertTrue(html.indexOf(cake) < html.indexOf(brownie), "排序键 A 应在 B 之前");
        assertTrue(html.indexOf(brownie) < html.indexOf(apple), "无排序键的页面按标题排最后");
    }

    @Test
    void categoryAliasRedirectsAndLeadingColonIsPlainLink() throws Exception {
        WikiUser user = insertUser("分类别名用户" + System.nanoTime());
        String category = "别名分类" + System.nanoTime();

        // Category:X → 302 到 分类:X
        mockMvc.perform(get("/wiki/{title}", "Category:" + category))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith(
                        URLEncoder.encode("分类:" + category, StandardCharsets.UTF_8).replace("+", "%20"))));

        // [[:分类:X]] 是普通链接，不归类
        String page = "仅链接页" + System.nanoTime();
        wikiPageService.editPage(user, page, "见 [[:分类:" + category + "|这个分类]]");
        mockMvc.perform(get("/wiki/{title}", page))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("这个分类")))
                .andExpect(content().string(containsString("/wiki/分类:" + category)))
                .andExpect(content().string(not(containsString("class=\"catlinks\""))))
                .andExpect(content().string(not(containsString("catlinks-spacer"))));
        mockMvc.perform(get("/wiki/{title}", "分类:" + category))
                .andExpect(content().string(not(containsString(page))));
    }

    @Test
    void templateCategoriesApplyAndTemplateUsageIsTraceable() throws Exception {
        WikiUser user = insertUser("模板追踪用户" + System.nanoTime());
        String dessert = "模板分类" + System.nanoTime();
        String template = "甜品标注" + System.nanoTime();
        String page = "提拉米苏" + System.nanoTime();
        wikiPageService.editPage(user, "模板:" + template, "[[分类:" + dessert + "]]");
        wikiPageService.editPage(user, page, "{{" + template + "}} 提拉米苏正文");

        // 模板里的分类作用到被嵌入的页面
        mockMvc.perform(get("/wiki/{title}", page))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"catlinks\"")))
                .andExpect(content().string(containsString(dessert)));
        mockMvc.perform(get("/wiki/{title}", "分类:" + dessert))
                .andExpect(content().string(containsString(page)));

        // 反查：使用模板的页面在链入页面里带「（嵌入）」标记（仿 MediaWiki）
        mockMvc.perform(get("/wiki/{title}", "特殊:链入页面").param("page", "模板:" + template))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(page)))
                .andExpect(content().string(containsString("（嵌入）")));

        // 正查：页面信息里列出使用的模板与所属分类
        mockMvc.perform(get("/wiki/{title}", "特殊:页面信息").param("page", page))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("使用的模板")))
                .andExpect(content().string(containsString("模板:" + template)))
                .andExpect(content().string(containsString("所属分类")))
                .andExpect(content().string(containsString("分类:" + dessert)));
    }

    @Test
    void categoriesListPageAggregatesCounts() throws Exception {
        WikiUser user = insertUser("分类列表用户" + System.nanoTime());
        String unique = String.valueOf(System.nanoTime());
        String category = "聚合分类" + unique;
        wikiPageService.editPage(user, "聚合页一" + unique, "A [[分类:" + category + "]]");
        wikiPageService.editPage(user, "聚合页二" + unique, "B [[分类:" + category + "]]");

        mockMvc.perform(get("/wiki/{title}", "特殊:分类列表"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(category)))
                .andExpect(content().string(containsString("（2 个页面）")));
    }

    // ==================== 工具 ====================

    private WikiUser insertUser(String username) {
        WikiUser user = new WikiUser();
        user.setUsername(username);
        user.setPassword("x");
        user.setEmail("");
        wikiUserMapper.insert(user);
        return user;
    }
}
