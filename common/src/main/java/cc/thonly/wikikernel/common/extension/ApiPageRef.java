package cc.thonly.wikikernel.common.extension;

/**
 * Action API 里的页面引用。
 *
 * @param id             页面 ID（不存在为 0）
 * @param title          规范化后（或原样）的标题
 * @param missing        页面不存在
 * @param redirectTarget 重定向目标标题（非重定向为 null）
 */
public record ApiPageRef(long id, String title, boolean missing, String redirectTarget) {
}
