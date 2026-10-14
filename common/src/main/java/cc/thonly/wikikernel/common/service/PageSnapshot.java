package cc.thonly.wikikernel.common.service;

import java.time.LocalDateTime;

/**
 * 页面快照（供插件读取页面信息，见 {@link WikiPageStore}）。
 *
 * @param id          页面 ID
 * @param title       标题（含命名空间）
 * @param redirect    是否重定向页
 * @param contentPage 是否内容命名空间（可计为条目；特殊 / 分类 / 模板 / 文件 / 用户 / 系统页面等为 false）
 * @param length      最新正文 UTF-8 字节数
 * @param updatedAt   最后更新时间（可能为空）
 */
public record PageSnapshot(long id, String title, boolean redirect, boolean contentPage, int length,
                           LocalDateTime updatedAt) {
}
