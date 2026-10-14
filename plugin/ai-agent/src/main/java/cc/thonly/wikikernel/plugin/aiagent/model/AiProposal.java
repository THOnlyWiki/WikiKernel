package cc.thonly.wikikernel.plugin.aiagent.model;

import java.time.LocalDateTime;

/**
 * Agent 提出的一个「待确认编辑」：**必须**由用户在界面上确认（看到渲染预览与差异，可以自己改正文）后才提交；
 * 确认后以用户名义**直接保存**（不走审核队列），并按「AI 协助」打上水印标记。
 *
 * @param id              提案 id（会话内唯一）
 * @param title           目标页面标题（已规范化）
 * @param content         代理建议的完整新正文（Wikitext）
 * @param comment         编辑摘要（用户确认时可改）
 * @param baseRevisionId  代理读到的版本 id（null = 当时页面不存在 → 新建）
 * @param creating        true = 新建页面
 * @param baseSize        基准版本字节数（界面展示「+N 字节」）
 * @param previewHtml     渲染后的预览 HTML
 * @param diffHtml        与基准版本的差异 HTML（新建时为空串）
 * @param createdAt       生成时间
 */
public record AiProposal(
        String id,
        String title,
        String content,
        String comment,
        Long baseRevisionId,
        boolean creating,
        long baseSize,
        String previewHtml,
        String diffHtml,
        LocalDateTime createdAt) {
}
