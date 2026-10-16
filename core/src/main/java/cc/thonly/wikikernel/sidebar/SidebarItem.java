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

    /**
     * 原始键：{@code MediaWiki:Sidebar} 里写的那段文字，**消息键解析 / 本地化之前**的值。
     * <p>
     * 皮肤用它生成 MediaWiki 风格的条目 id（{@code id="n-<键>"}）：
     * {@code ** mainpage|mainpage-description} → {@code id="n-mainpage-description"}；
     * 侧栏图标规则（皮肤自带的 {@code #n-mainpage-description} 与站点
     * {@code WikiKernel:<皮肤>.css} 里的 {@code #n-沙盒} 等）都按这个 id 匹配——与 MediaWiki 一致
     * （MediaWiki 也是用原始文字做 id，显示文字才走消息解析）。
     */
    String key;

    /** 全参构造（Lombok 在有自定义构造时不再生成，这里显式写出来）。 */
    public SidebarItem(String label, String href, boolean external, String key) {
        this.label = label;
        this.href = href;
        this.external = external;
        this.key = key;
    }

    /** 兼容构造：没有单独键值时，键 = 显示文字（站点自己写的条目两者本来就相同）。 */
    public SidebarItem(String label, String href, boolean external) {
        this(label, href, external, label);
    }
}
