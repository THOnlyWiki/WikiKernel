package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;

import java.util.Optional;

/**
 * 页面预览（仿 MediaWiki 的 Popups / Page Previews 扩展）：
 * 桌面端把鼠标悬停在页面链接上时，显示该页的摘要（首段纯文本）与缩略图。
 * <p>
 * 数据由 {@code GET /api/page-preview?title=…} 提供；前端脚本 {@code /js/popups.js}
 * 由功能开关 {@code features.popups}（默认开启）控制加载。
 */
public interface PagePreviewService {

    /** 摘要的最大字数。 */
    int EXTRACT_LIMIT = 350;

    /**
     * 生成页面预览。
     *
     * @param title  链接指向的标题（会做规范化；重定向会跟随一次）
     * @param viewer 当前用户（用于读取权限判断）
     * @return 预览数据；页面不存在 / 不可读 / 特殊页等返回空
     */
    Optional<Preview> preview(String title, WikiUser viewer);

    /**
     * 预览数据。
     *
     * @param title        页面标题
     * @param url          页面地址（站内路径）
     * @param extract      摘要（纯文本，已去掉 wikitext / HTML 标记）
     * @param thumbnailUrl 缩略图地址（无图时为 null）
     * @param thumbnailAlt 缩略图 alt（文件名；无图时为 null）
     */
    record Preview(String title, String url, String extract, String thumbnailUrl, String thumbnailAlt) {
    }
}
