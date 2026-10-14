package cc.thonly.wikikernel.db.mapper;

import cc.thonly.wikikernel.common.data.WikiCompiledScript;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 编译产物缓存（{@code wiki_compiled_script}：TypeScript 等源码页面的编译结果）。 */
@Mapper
public interface WikiCompiledScriptMapper extends BaseMapper<WikiCompiledScript> {
}
