package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.extension.SpecialPage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 特殊页面注册表：前缀判断 + 按名字/别名解析（core Bean 与插件扩展统一）。
 */
public interface SpecialWikiPageService {

    /** 是否是特殊页（前缀可在 wikikernel.special-page.prefixes 配置）。 */
    boolean isSpecialPage(String title);

    /** 解析特殊页；返回空表示"是特殊页前缀但没有对应实现"。 */
    Optional<SpecialPageMatch> resolve(String title, Map<String, String> parameters);

    /** 当前注册表里的所有特殊页（按名字去重、core 优先，保持注册顺序）。 */
    List<SpecialPage> listRegisteredPages();
}
