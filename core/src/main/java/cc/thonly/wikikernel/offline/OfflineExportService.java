package cc.thonly.wikikernel.offline;

import cc.thonly.wikikernel.common.data.WikiUser;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 把当前站点导出为可离线浏览的 ZIP。
 *
 * <p>导出使用当前运行中的应用进行真实渲染（保留皮肤、插件 head、模板样式），
 * 然后把站内链接、静态资源和上传图片改写为离线包内的相对路径。任务异步执行。</p>
 */
public interface OfflineExportService {

    /**
     * 创建后台导出任务。
     *
     * @param baseUrl 本机回环地址（例如 {@code http://127.0.0.1:18080}）
     * @param skinId  本次导出使用的皮肤 id
     * @param user    发起导出的行政员（用于页面可读性过滤）
     * @param limit   调试用最大页面数；{@code <= 0} 表示导出全部
     * @param cookie  发起导出的管理员会话 Cookie（用于回环请求渲染受限页面；可为空）
     * @return 任务
     */
    default OfflineExportJob start(String baseUrl, String skinId, WikiUser user, int limit, String cookie) {
        return start(baseUrl, skinId, user, limit, cookie, java.util.List.of());
    }

    /**
     * 导出指定页面（用于诊断 / 单页修复）；{@code selectedTitles} 为空时导出全部页面。
     * 指定页面模式不追踪站内链接，因此适合快速验证单个复杂页面。
     */
    OfflineExportJob start(String baseUrl, String skinId, WikiUser user, int limit,
                           String cookie, java.util.List<String> selectedTitles);

    Optional<OfflineExportJob> find(String id);

    Optional<Path> archive(String id);

    /** 当前用户可导出的页面数量；用于特殊页显示预计规模。 */
    int countExportablePages(WikiUser user);
}
