package cc.thonly.wikikernel.common.extension;

import java.util.Map;

/**
 * {@link HtmlHeadProvider} 的上下文：当前页面、请求信息与页面属性。
 */
public interface HeadContext {

    /** 页面 ID（特殊页 / 不存在时为 0）。 */
    long pageId();

    /** 页面标题（含命名空间）。 */
    String pageTitle();

    /** 请求路径（如 {@code /wiki/首页}）。 */
    String path();

    /** 查询参数（如 {@code action}、{@code oldid}）。 */
    Map<String, String> query();

    /** 页面属性（{@code wiki_page_property}，渲染时写入、保存后持久）。 */
    Map<String, String> properties();

    /** 站点名称。 */
    String siteName();

    /** 站点根地址（如 {@code http://localhost:8080}，不带尾斜杠）。 */
    String baseUrl();

    /** 页面完整地址（{@link #baseUrl()} + {@link #path()}）。 */
    default String pageUrl() {
        return baseUrl() + path();
    }

    /** 文件 URL（给文件名，如 {@code Logo.png}；不是图片 / 不存在返回空串）。 */
    String fileUrl(String fileName);

    /** 图片宽度（像素；非图片 / 不存在返回 0）。 */
    int fileWidth(String fileName);

    /** 图片高度（像素；非图片 / 不存在返回 0）。 */
    int fileHeight(String fileName);
}
