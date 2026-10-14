package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：媒体文件嵌入渲染（仿 MediaWiki TimedMediaHandler）。
 * <p>
 * 词条里 {@code [[文件:X.webm]]} / {@code [[文件:X.ogg|thumb|300px]]} 时，core 按 {@link #order()}
 * 升序询问每个渲染器：返回非 {@code null} 就用它替换默认内容（图片 / 图标占位框）。
 * <p>
 * 只负责**内容部分**：{@code thumb} 的 {@code <figure>} 包裹、{@code left/right/center} 对齐与说明文字
 * 仍由 core 统一处理，插件不必重复实现。
 */
public interface FileEmbedRenderer extends Extension {

    /** 执行顺序，值越小越先执行。 */
    default int order() {
        return 0;
    }

    /** 返回该文件的播放器 / 预览 HTML；{@code null} = 不处理，交给下一个渲染器或 core 默认行为。 */
    String render(FileEmbedCall call);
}
