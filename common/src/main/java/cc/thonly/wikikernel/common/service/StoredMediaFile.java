package cc.thonly.wikikernel.common.service;

/**
 * 已存储的媒体文件。
 *
 * @param name      规范文件名（如 {@code 动画.swf}）
 * @param url       文件直链（{@code /files/…}）
 * @param pageUrl   文件说明页地址（{@code /wiki/文件:…}）
 * @param image     是否图片
 * @param mediaKind 媒体类型（{@code image} / {@code audio} / {@code video} / 其它）
 */
public record StoredMediaFile(String name, String url, String pageUrl, boolean image, String mediaKind) {
}
