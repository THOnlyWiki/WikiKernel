package cc.thonly.wikikernel.common.service;

/**
 * 一条数值型页面属性。
 *
 * @param pageId 页面 ID
 * @param value  数值
 */
public record PagePropertyValue(long pageId, long value) {
}
