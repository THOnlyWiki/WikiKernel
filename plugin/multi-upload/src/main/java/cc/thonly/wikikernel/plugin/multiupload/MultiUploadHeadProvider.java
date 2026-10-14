package cc.thonly.wikikernel.plugin.multiupload;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;

import java.util.List;

/**
 * 多文件批量上传的前端注入（站点级；脚本自己判断页面上有没有上传表单 / 编辑器工具栏）：
 * <ul>
 *     <li>{@code window.WIKI_CONFIG.multiUpload = true}（core 的单文件流程与插件脚本都读它）；</li>
 *     <li>批量上传用到的文案（{@code window.WIKI_MESSAGES}，与 core 单文件文案合并）；</li>
 *     <li>{@code /plugins/multi-upload/multi-upload.js}。</li>
 * </ul>
 */
public class MultiUploadHeadProvider implements HtmlHeadProvider {

    private final MultiUploadSupport support;

    public MultiUploadHeadProvider(MultiUploadSupport support) {
        this.support = support;
    }

    @Override
    public int order() {
        return 750;
    }

    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (!support.enabled()) {
            return List.of();
        }
        return List.of(
                "<script>window.WIKI_CONFIG = window.WIKI_CONFIG || {};"
                        + "window.WIKI_CONFIG.multiUpload = true;"
                        + "window.WIKI_MESSAGES = Object.assign(window.WIKI_MESSAGES || {}, {"
                        + "multiUploadHint: " + json(support.message("files.multiUploadHint", "")) + ","
                        + "multiListLabel: " + json(support.message("files.multiListLabel", "已选择的文件")) + ","
                        + "multiNameHint: " + json(support.message("files.multiNameHint", "")) + ","
                        + "multiDescriptionHint: " + json(support.message("files.multiDescriptionHint", "")) + ","
                        + "multiUploading: " + json(support.message("files.multiUploading", "正在上传（{0}/{1}）…")) + ","
                        + "multiSummary: " + json(support.message("files.multiSummary", "")) + ","
                        + "multiSummaryPending: " + json(support.message("files.multiSummaryPending", "")) + ","
                        + "multiRemove: " + json(support.message("files.multiRemove", "移除")) + ","
                        + "multiStatusQueued: " + json(support.message("files.multiStatusQueued", "等待上传")) + ","
                        + "multiStatusUploading: " + json(support.message("files.multiStatusUploading", "上传中…")) + ","
                        + "multiStatusDone: " + json(support.message("files.multiStatusDone", "已上传")) + ","
                        + "multiStatusFailed: " + json(support.message("files.multiStatusFailed", "失败")) + ","
                        + "uploadChooseMany: " + json(support.message("files.chooseMany", "选择图片（可多选）："))
                        + "});</script>",
                "<script defer src=\"/plugins/multi-upload/multi-upload.js\"></script>");
    }

    private static String json(String value) {
        return "\"" + (value == null ? "" : value
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("<", "\\u003c").replace(">", "\\u003e")) + "\"";
    }
}
