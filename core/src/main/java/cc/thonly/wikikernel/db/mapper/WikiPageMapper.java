package cc.thonly.wikikernel.db.mapper;

import cc.thonly.wikikernel.common.data.WikiPage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface WikiPageMapper extends BaseMapper<WikiPage> {

    /** 乐观锁：只有页面仍指向 expectedRevisionId 时才推进最新版本。 */
    @Update("UPDATE wiki_page SET latest_revision_id = #{revisionId}, update_at = #{updateAt} "
            + "WHERE id = #{pageId} AND latest_revision_id = #{expectedRevisionId}")
    int updateLatestIfMatches(@Param("pageId") Long pageId,
                              @Param("expectedRevisionId") Long expectedRevisionId,
                              @Param("revisionId") Long revisionId,
                              @Param("updateAt") java.time.LocalDateTime updateAt);
}
