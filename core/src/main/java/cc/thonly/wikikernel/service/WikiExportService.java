package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.transfer.ExportData;

import java.util.List;

/**
 * 页面导出（{@code 特殊:导出页面} / {@code GET|POST /api/export}）：
 * 收集可导出的页面与版本（按访问规则过滤，无权查看的页面不出现、不泄露标题）。
 */
public interface WikiExportService {

    /**
     * 收集导出数据。
     *
     * @param titles    页面标题（去重后处理；模板带出的页面追加在后面）
     * @param history   true = 完整历史；false = 每个页面只取当前版本
     * @param templates true = 递归带上被引用的模板页
     * @param user      当前用户（匿名用 null；用于查看权限过滤）
     * @param baseUrl   站点首页 URL（写入导出文件的 siteinfo）
     */
    ExportData collect(List<String> titles, boolean history, boolean templates, WikiUser user, String baseUrl);

    /** 某命名空间下当前用户可查看的全部标题（「添加命名空间全部页面」用；按标题升序、受导出上限约束）。 */
    List<String> namespaceTitles(String namespaceId, WikiUser user);
}
