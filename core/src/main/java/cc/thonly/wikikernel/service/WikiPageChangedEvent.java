package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiPage;

/**
 * 页面变更事件（页面创建 / 编辑 / 移动 / 删除后发布）：
 * 分类索引等“派生数据”通过监听该事件重建，避免服务之间循环依赖。
 */
public record WikiPageChangedEvent(Long pageId, String title, boolean deleted) {
}
