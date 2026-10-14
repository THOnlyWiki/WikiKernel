package cc.thonly.wikikernel.common.service;

import java.util.List;
import java.util.Map;

/**
 * 行级差异桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 与 core 的差异对比页 / 编辑冲突提示**共用同一套实现与模板行模型**
 * （{@code .diff} 两栏 + 内联高亮 {@code .diffchange}），插件（如审核队列的
 * 「当前版本 → 待审内容」）直接把它交给模板即可，不必自己抄一份 diff 算法。
 */
public interface WikiDiff {

    /**
     * 生成两栏差异行。
     * <p>
     * 每行是一个只读 Map，键与 core 的差异模板一致：
     * {@code oldNo} / {@code oldMarker} / {@code oldClass} / {@code oldBefore} / {@code oldChange} /
     * {@code oldAfter} 与对应的 {@code new*} 一组（{@code oldBefore}/{@code oldAfter}/{@code newBefore}/
     * {@code newAfter} 在空单元格里是 {@code \u00a0}）。
     *
     * @param oldContent 旧（当前版本）正文
     * @param newContent 新（待审）正文
     */
    List<Map<String, Object>> rows(String oldContent, String newContent);
}
