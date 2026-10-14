package cc.thonly.wikikernel.transfer;

import java.time.LocalDateTime;

/**
 * 待导入的单个版本（各格式解析后的统一中间模型）。
 *
 * @param time    编辑时间（服务器时区；解析不出为 null，导入时用当前时间）
 * @param username 贡献者用户名（本地存在则关联账号；不存在则匿名）
 * @param userId  原站用户 ID（仅 WikiKernel 格式提供，不直接沿用）
 * @param comment 编辑摘要
 * @param content 正文
 * @param bot     是否为机器人编辑（仅 WikiKernel 格式提供）
 * @param tags    变更标签（仅 WikiKernel 格式提供）
 * @param deleted 版本是否被隐藏（仅 WikiKernel 格式提供）
 */
public record ImportedRevision(LocalDateTime time,
                               String username,
                               Long userId,
                               String comment,
                               String content,
                               boolean bot,
                               String tags,
                               boolean deleted) {
}
