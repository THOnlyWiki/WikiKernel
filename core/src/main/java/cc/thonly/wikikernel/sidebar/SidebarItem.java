package cc.thonly.wikikernel.sidebar;

import lombok.Value;

/** 侧边栏条目。 */
@Value
public class SidebarItem {

    /** 显示文字。 */
    String label;

    /** 链接地址（内部为 /wiki/xxx，外部为原 URL）。 */
    String href;

    /** 是否外链（新窗口打开）。 */
    boolean external;
}
