package cc.thonly.wikikernel.log;

import cc.thonly.wikikernel.common.data.WikiLog;
import lombok.Data;

/** 日志页/权限页展示用的一行（文案已按当前语言格式化）。 */
@Data
public class LogEntryView {

    private WikiLog log;

    /** 操作者用户名（null = 匿名/系统）。 */
    private String actorName;

    /** 目标展示文本（页面标题 / 用户名的本地解析结果）。 */
    private String targetText;

    /** 已本地化的动作句，如“创建了页面「首页」”。 */
    private String actionText;

    /** 动作句在目标之前的部分，如“创建了页面「” —— 模板块把目标渲染成链接时用。 */
    private String actionBefore;

    /** 动作句在目标之后的部分，如“」”。 */
    private String actionAfter;

    /** 目标链接（用户 → 用户页；页面 / 文件 → 页面；IP / CIDR → 用户贡献）；无法判断时为 null。 */
    private String targetUrl;

    /** 已本地化的详情，如“已授予「管理员」”。 */
    private String detailText;
}
