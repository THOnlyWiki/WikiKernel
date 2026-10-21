package cc.thonly.wikikernel.offline;

import java.nio.file.Path;

/**
 * 导出包中的一个页面。slug 在 {@code pages/} 下，使用短哈希避免标题里的非法文件名。
 */
record OfflinePage(String title, String slug, String routePath, Path file) {

    /** 从另一个页面指向本页的相对链接（两个页面都在 {@code pages/} 下）。 */
    String linkFrom(OfflinePage current) {
        if (current == null) {
            return "pages/" + slug;
        }
        return current.file().getParent().relativize(file).toString().replace('\\', '/');
    }

    /** 从本页正文指向 assets/ 目录下资源的相对前缀。 */
    String assetPrefix() {
        return "../assets/";
    }
}
