package cc.thonly.wikikernel.service;

/**
 * 站内页面存在性（红链判断用，仿 MediaWiki 的「未知页面红链」）。
 */
public interface PageExistenceService {

    /**
     * 目标标题的页面是否存在。
     * <p>
     * 已删除 / 已隐藏的页面视为不存在；{@code 特殊:} / {@code Special:} 前缀的特殊页始终认为存在
     * （它们没有页面行，由特殊页注册中心提供）。
     *
     * @param title 页面标题（可含下划线 / 空格；编号前缀会被规范化）
     */
    boolean exists(String title);
}
