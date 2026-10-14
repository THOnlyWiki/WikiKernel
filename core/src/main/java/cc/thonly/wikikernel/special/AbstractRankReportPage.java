package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import org.springframework.context.MessageSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 排行类报告特殊页基类（仿 MediaWiki 的 Special:MostLinkedPages / Special:MostCategories /
 * Special:MostRevisions 等）：子类给出「标题 + 计数」的有序列表，基类渲染成两列表格。
 * <p>
 * 子类负责权限过滤（只返回当前用户可读的标题）与排序；基类只做展示裁剪（最多 {@value #REPORT_LIMIT} 条）。
 */
abstract class AbstractRankReportPage extends AbstractReportPage {

    /** 单次扫描 / 统计上限（重扫描类报告，见 {@code AbstractReportPage} 的说明）。 */
    protected static final int SCAN_LIMIT = 300;

    /** 索引类报告（{@code wiki_page_template} / {@code wiki_page_category}）的单次读取上限。 */
    protected static final int INDEX_SCAN_LIMIT = 5000;

    protected AbstractRankReportPage(MessageSource messageSource) {
        super(messageSource);
    }

    /** 排行条目：标题（规范标题，用于链接）+ 显示文字 + 计数。 */
    protected record Rank(String title, String label, int count) {

        /** 显示文字默认与标题相同（页面类报告）。 */
        Rank(String title, int count) {
            this(title, title, count);
        }
    }

    /** 已按需要的顺序排好的条目（已做可读性过滤）。 */
    protected abstract List<Rank> ranks(SpecialPageRequest request);

    /** 表头（默认「页面 / 次数」）。 */
    protected List<String> headers() {
        return List.of(message("report.col.page"), message("report.col.count"));
    }

    @Override
    public String render(SpecialPageRequest request) {
        List<List<String>> rows = new ArrayList<>();
        for (Rank rank : ranks(request)) {
            if (rows.size() >= REPORT_LIMIT) {
                break;
            }
            rows.add(List.of(link(rank.title(), rank.label()), String.valueOf(rank.count())));
        }
        return page(table(headers(), rows));
    }
}
