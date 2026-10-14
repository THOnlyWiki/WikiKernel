package cc.thonly.wikikernel.db.mapper;

import cc.thonly.wikikernel.common.data.WikiInterwiki;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 跨 Wiki 前缀（{@code wiki_interwiki}）。 */
@Mapper
public interface WikiInterwikiMapper extends BaseMapper<WikiInterwiki> {
}
