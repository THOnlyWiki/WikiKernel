package cc.thonly.wikikernel.plugin.fingerprintlogin.model;

import cc.thonly.wikikernel.plugin.fingerprintlogin.data.WikiFingerprint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理界面（参数设置分区 / 特殊:指纹登录 / 接口）共用的指纹视图：
 * 只暴露用户自己需要看到的字段，**不含指纹摘要**（摘要不回显给前端，避免被复制）。
 *
 * @param id            指纹 id（删除用）
 * @param label         用户起的名字
 * @param userAgent     UA 摘要
 * @param createdAtText 登记时间（已按当前语言格式化）
 * @param lastUsedText  最近使用时间（从未使用为空串，界面显示「从未使用」）
 * @param used          是否使用过
 */
public record FingerprintView(long id,
                              String label,
                              String userAgent,
                              String createdAtText,
                              String lastUsedText,
                              boolean used) {

    /** 由实体 + 已格式化时间构造。 */
    public static FingerprintView of(WikiFingerprint row, String createdAtText, String lastUsedText) {
        return new FingerprintView(row.getId() == null ? 0L : row.getId(),
                row.getLabel() == null ? "" : row.getLabel(),
                row.getUserAgent() == null ? "" : row.getUserAgent(),
                createdAtText == null ? "" : createdAtText,
                lastUsedText == null ? "" : lastUsedText,
                lastUsedText != null && !lastUsedText.isBlank());
    }

    /** 模型视图（Thymeleaf 模板用；与 JSON 视图字段一致）。 */
    public Map<String, Object> asModel() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("label", label);
        row.put("userAgent", userAgent);
        row.put("createdAtText", createdAtText);
        row.put("lastUsedText", lastUsedText);
        row.put("used", used);
        return row;
    }
}
