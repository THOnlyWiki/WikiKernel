package cc.thonly.wikikernel.sidebar;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 侧边栏配置：
 *
 * <pre>
 * wikikernel:
 *   sidebar:
 *     page: WikiKernel:Sidebar
 * </pre>
 */
@ConfigurationProperties(prefix = "wikikernel.sidebar")
public class SidebarProperties {

    /** 控制侧边栏内容的页面标题。 */
    private String page = "WikiKernel:Sidebar";

    public String getPage() {
        return page;
    }

    public void setPage(String page) {
        this.page = page;
    }
}
