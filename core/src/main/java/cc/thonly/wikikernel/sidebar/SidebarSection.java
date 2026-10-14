package cc.thonly.wikikernel.sidebar;

import lombok.Value;

import java.util.List;

/** 侧边栏分组。 */
@Value
public class SidebarSection {

    /** 分组标题，可为空。 */
    String title;

    /** 分组下的条目。 */
    List<SidebarItem> items;
}
