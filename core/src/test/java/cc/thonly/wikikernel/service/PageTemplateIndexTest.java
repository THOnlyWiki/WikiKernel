package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiPageTemplate;
import cc.thonly.wikikernel.db.mapper.WikiPageTemplateMapper;
import cc.thonly.wikikernel.service.impl.PageIndexGate;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板使用索引（{@code wiki_page_template}）：
 * 保存 / 删除时事件驱动实时重建；反查用索引找候选 + 对最新正文实时扫描核对；
 * {@code {{裸名}}} / {@code {{模板:名}}} / {@code {{Template:名}}} 都能命中；脏索引行会被实时扫描过滤。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class PageTemplateIndexTest {

    @Autowired
    private WikiPageService wikiPageService;

    @Autowired
    private PageTemplateService pageTemplateService;

    @Autowired
    private PageLinkService pageLinkService;

    @Autowired
    private WikiPageTemplateMapper templateMapper;

    @Autowired
    private PageIndexGate indexGate;

    @Test
    void indexIsMaintainedInRealTimeAndLookupChecksLiveContent() {
        String suffix = String.valueOf(System.nanoTime());
        String templateName = "索引模板" + suffix;
        String template = "模板:" + templateName;
        String pageA = "索引页A" + suffix;
        String pageB = "索引页B" + suffix;
        String pageC = "索引页C" + suffix;

        wikiPageService.editPage(null, template, "模板正文");
        wikiPageService.editPage(null, pageA, "{{" + templateName + "}} 裸名调用");
        wikiPageService.editPage(null, pageB, "{{" + template + "}} 带前缀调用");
        wikiPageService.editPage(null, pageC, "没有使用模板");
        indexGate.awaitIdle();

        // 索引已实时写入（裸名调用会被规范化为 模板:X，甲乙各一行）
        assertEquals(2L, indexRowCount(template));

        // 反查：带前缀 / 裸名 / 英文别名 三种查询键都能命中；结果按标题排序
        assertEquals(List.of(pageA, pageB), titlesOf(pageTemplateService.pagesUsing(template, 50)));
        assertEquals(List.of(pageA, pageB), titlesOf(pageTemplateService.pagesUsing(templateName, 50)));
        assertEquals(List.of(pageA, pageB),
                titlesOf(pageTemplateService.pagesUsing("Template:" + templateName, 50)));
        // PageLinkService 的公开反查（链入页面用）走同一索引
        assertEquals(List.of(pageA, pageB), titlesOf(pageLinkService.pagesTranscluding(template, 50)));

        // 脏索引行（页面实际没在用该模板）会被查询时的实时扫描过滤掉
        templateMapper.insert(new WikiPageTemplate(null, pageIdOf(pageC), template));
        assertEquals(List.of(pageA, pageB), titlesOf(pageTemplateService.pagesUsing(template, 50)));

        // 实时更新：甲去掉模板调用 → 索引减少（只剩乙 + 脏行）
        wikiPageService.editPage(null, pageA, "不再使用模板");
        indexGate.awaitIdle();
        assertEquals(2L, indexRowCount(template));
        assertEquals(List.of(pageB), titlesOf(pageTemplateService.pagesUsing(template, 50)));

        // 删除乙 → 索引行清空（只剩脏行），反查也没有结果
        wikiPageService.deletePage(null, pageB);
        indexGate.awaitIdle();
        assertEquals(1L, indexRowCount(template));
        assertTrue(pageTemplateService.pagesUsing(template, 50).isEmpty());
    }

    // ==================== 工具 ====================

    private long indexRowCount(String templateTitle) {
        Long count = templateMapper.selectCount(Wrappers.<WikiPageTemplate>lambdaQuery()
                .eq(WikiPageTemplate::getTemplateTitle, templateTitle));
        return count == null ? 0L : count;
    }

    private List<String> titlesOf(List<WikiPage> pages) {
        return pages.stream().map(WikiPage::getTitle).toList();
    }

    private Long pageIdOf(String title) {
        return wikiPageService.getByTitle(title).orElseThrow().getId();
    }
}
