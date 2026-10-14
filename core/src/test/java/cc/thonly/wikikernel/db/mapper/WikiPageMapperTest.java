package cc.thonly.wikikernel.db.mapper;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证「实体（common） + Auto-DDL + MyBatis-Plus」整条链路：
 * 自动建 wiki_page / wiki_revision 两张表、超长正文、LocalDateTime 读写。
 */
@SpringBootTest(properties = "wikikernel.database.file=:memory:")
class WikiPageMapperTest {

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiRevisionMapper wikiRevisionMapper;

    @Test
    void insertAndLoadPageWithLongRevisionContent() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 4, 12, 0, 0);

        WikiPage page = new WikiPage();
        page.setTitle("测试词条");
        page.setCreateAt(now);
        page.setUpdateAt(now);
        assertEquals(1, wikiPageMapper.insert(page));
        // IdType.AUTO：主键由数据库自增生成，insert 后应回填到实体
        assertNotNull(page.getId(), "数据库自增主键应回填到实体");

        WikiRevision revision = new WikiRevision();
        revision.setPageId(page.getId());
        // 远超 255 字符，验证正文不受长度限制
        revision.setContent("很长很长的正文……".repeat(4000));
        revision.setComment("初始版本");
        revision.setCreateAt(now);
        assertEquals(1, wikiRevisionMapper.insert(revision));
        assertNotNull(revision.getId(), "版本主键也应回填");

        page.setLatestRevisionId(revision.getId());
        wikiPageMapper.updateById(page);

        WikiPage loadedPage = wikiPageMapper.selectById(page.getId());
        WikiRevision loadedRevision = wikiRevisionMapper.selectById(revision.getId());
        assertNotNull(loadedPage);
        assertNotNull(loadedRevision);
        assertEquals("测试词条", loadedPage.getTitle());
        assertEquals(revision.getId(), loadedPage.getLatestRevisionId());
        assertEquals(page.getId(), loadedRevision.getPageId());
        assertEquals(revision.getContent(), loadedRevision.getContent());
        assertEquals(now, loadedRevision.getCreateAt());
    }
}
