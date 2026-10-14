package cc.thonly.wikikernel.common.extension;

/**
 * 词条里嵌入媒体文件（{@code [[文件:X.webm|thumb|300px|说明]]}）时的调用信息，
 * 交给 {@link FileEmbedRenderer} 决定怎么渲染。
 */
public interface FileEmbedCall {

    /** 规范文件名（空格已转下划线，如 {@code 演示.webm}）。 */
    String fileName();

    /** 文件直链（{@code /files/…}）。 */
    String url();

    /** 文件说明页地址（{@code /wiki/文件:…}）。 */
    String pageUrl();

    /** 媒体类型：{@code image} / {@code audio} / {@code video} / {@code other}。 */
    String mediaKind();

    /** MIME 类型（可能为空）。 */
    String mimeType();

    /** 文件大小（字节；未知为 0）。 */
    long size();

    /** 请求的显示宽度（{@code [[文件:X|300px]]} 的 300；未指定为 {@code null}）。 */
    Integer width();

    /** 说明文字（{@code [[文件:X|thumb|说明]]} 的「说明」；没有为空串）。 */
    String caption();

    /** 替代文字（{@code alt=…}；默认是「文件:名字」）。 */
    String alt();

    /** 模板参数里的命名属性（如 {@code start=10}，来自 {@code [[文件:X|start=10]]}；未知属性也在这里）。 */
    java.util.Map<String, String> options();
}
