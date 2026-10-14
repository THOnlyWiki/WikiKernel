package cc.thonly.wikikernel.transfer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一次导出的完整数据（三个序列化器共用）。
 *
 * @param siteName   站点名
 * @param baseUrl    站点首页 URL（siteinfo.base）
 * @param generator  生成器标识
 * @param exportedAt 导出时间（服务器时区）
 * @param truncated  是否因限额被截断
 * @param pages      页面（保持用户输入顺序）
 * @param userNames  用户 ID → 用户名（版本贡献者展示）
 */
public record ExportData(String siteName,
                         String baseUrl,
                         String generator,
                         LocalDateTime exportedAt,
                         boolean truncated,
                         List<ExportPageData> pages,
                         Map<Long, String> userNames) {
}
