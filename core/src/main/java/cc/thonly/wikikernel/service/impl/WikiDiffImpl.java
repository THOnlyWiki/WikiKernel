package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.service.WikiDiff;
import cc.thonly.wikikernel.wikitext.DiffRows;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 行级差异桥：直接复用 core 差异对比页的 {@link DiffRows}（同一套两栏行模型与内联高亮），
 * 插件（如审核队列的「当前版本 → 待审内容」）不必自己实现 diff 算法。
 */
@Service
public class WikiDiffImpl implements WikiDiff {

    @Override
    public List<Map<String, Object>> rows(String oldContent, String newContent) {
        return DiffRows.build(oldContent == null ? "" : oldContent,
                newContent == null ? "" : newContent);
    }
}
