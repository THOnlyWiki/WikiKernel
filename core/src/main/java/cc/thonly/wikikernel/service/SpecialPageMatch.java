package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.extension.SpecialPage;

import java.util.Map;

/**
 * 匹配到的特殊页面。
 *
 * @param name       去前缀后的名字（如 "全部页面"）
 * @param page       页面实现
 * @param parameters 查询参数
 */
public record SpecialPageMatch(String name, SpecialPage page, Map<String, String> parameters) {
}
