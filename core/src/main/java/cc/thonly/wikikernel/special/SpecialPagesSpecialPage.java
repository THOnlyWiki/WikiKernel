package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.FeatureService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.SpecialWikiPageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:特殊页面} / {@code Special:SpecialPages}：列出注册表里的所有特殊页。
 * <p>
 * 排版仿 MediaWiki：顶部搜索框 + 目录 + 分组列表（{@link SpecialPageCategories}）；
 * 每个特殊页用 {@link SpecialPage#category()} 声明分组，未声明的归入「其他特殊页面」。
 * <p>
 * 用 {@link ObjectProvider} 延迟获取注册表，避免循环依赖
 * （注册表要收集所有 SpecialPage Bean，其中就包括本类）。
 */
@Component
public class SpecialPagesSpecialPage implements SpecialPage {

    /** 内置特殊页 → 分组（按 {@link SpecialPage#name()}）；未命中的用 {@link SpecialPage#category()}。 */
    private static final Map<String, String> BUILT_IN_CATEGORIES = builtInCategories();

    private final ObjectProvider<SpecialWikiPageService> specialWikiPageService;
    private final FeatureService featureService;
    private final PermissionService permissionService;
    private final MessageSource messageSource;

    public SpecialPagesSpecialPage(ObjectProvider<SpecialWikiPageService> specialWikiPageService,
                                   FeatureService featureService,
                                   PermissionService permissionService,
                                   MessageSource messageSource) {
        this.specialWikiPageService = specialWikiPageService;
        this.featureService = featureService;
        this.permissionService = permissionService;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "特殊页面";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.specialPages", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("SpecialPages");
    }

    @Override
    public String template() {
        return "special/special-pages";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        // 注册关闭且访问者无权创建账号时，索引里不列出「特殊:注册」
        boolean registerAvailable = featureService.registerEnabled()
                || (request.user() != null && permissionService.canManage(request.user().getId()));
        Map<String, List<Map<String, String>>> byCategory = new LinkedHashMap<>();
        for (SpecialPage page : specialWikiPageService.getObject().listRegisteredPages()) {
            if (RegisterSpecialPage.PAGE_NAME.equals(page.name()) && !registerAvailable) {
                continue;
            }
            String category = resolveCategory(page);
            byCategory.computeIfAbsent(category, key -> new ArrayList<>())
                    .add(Map.of("name", page.displayName(), "urlName", "特殊:" + page.name()));
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        for (String category : orderedCategories(byCategory)) {
            List<Map<String, String>> items = byCategory.get(category);
            if (items == null || items.isEmpty()) {
                continue;
            }
            items.sort(Comparator.comparing(item -> item.get("name"), String.CASE_INSENSITIVE_ORDER));
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("id", category);
            group.put("label", groupLabel(category));
            group.put("items", items);
            groups.add(group);
        }
        model.put("groups", groups);
    }

    /** 分组归属：特殊页自己声明了非 other 的分组就用它，否则查内置映射，最后归 other。 */
    private static String resolveCategory(SpecialPage page) {
        String declared = page.category();
        if (declared != null && !declared.isBlank() && !SpecialPageCategories.OTHER.equals(declared)) {
            return declared;
        }
        return BUILT_IN_CATEGORIES.getOrDefault(page.name(), SpecialPageCategories.OTHER);
    }

    /** 已知分组按固定顺序；未知分组（如插件自定义）按字母序插在 OTHER 之前。 */
    private static List<String> orderedCategories(Map<String, List<Map<String, String>>> byCategory) {
        List<String> ordered = new ArrayList<>();
        for (String category : SpecialPageCategories.ORDER) {
            if (byCategory.containsKey(category)) {
                ordered.add(category);
            }
        }
        List<String> extras = new ArrayList<>();
        for (String category : byCategory.keySet()) {
            if (!SpecialPageCategories.ORDER.contains(category)) {
                extras.add(category);
            }
        }
        extras.sort(String.CASE_INSENSITIVE_ORDER);
        if (!extras.isEmpty()) {
            int otherIndex = ordered.indexOf(SpecialPageCategories.OTHER);
            if (otherIndex < 0) {
                ordered.addAll(extras);
            } else {
                ordered.addAll(otherIndex, extras);
            }
        }
        return ordered;
    }

    private String groupLabel(String category) {
        return messageSource.getMessage("specialPages.group." + category, null, category,
                LocaleContextHolder.getLocale());
    }

    private static Map<String, String> builtInCategories() {
        Map<String, String> map = new LinkedHashMap<>();
        // 页面列表
        map.put("全部页面", SpecialPageCategories.PAGES);
        map.put("分类列表", SpecialPageCategories.PAGES);
        map.put("搜索", SpecialPageCategories.PAGES);        // 账号管理
        map.put("登录", SpecialPageCategories.LOGIN);
        map.put("注册", SpecialPageCategories.LOGIN);
        map.put("重置密码", SpecialPageCategories.LOGIN);
        map.put("参数设置", SpecialPageCategories.LOGIN);
        map.put("外观", SpecialPageCategories.LOGIN);
        // 用户与权限
        map.put("用户", SpecialPageCategories.USERS);
        map.put("用户权限", SpecialPageCategories.USERS);
        map.put("权限列表", SpecialPageCategories.USERS);
        map.put("用户贡献", SpecialPageCategories.USERS);
        map.put("用户核查", SpecialPageCategories.USERS);
        map.put("用户合并", SpecialPageCategories.USERS);
        map.put("权限配置", SpecialPageCategories.USERS);
        map.put("Gadgets", SpecialPageCategories.USERS);
        // 滥用过滤器
        map.put("滥用过滤器", SpecialPageCategories.CHANGES);
        map.put("滥用日志", SpecialPageCategories.CHANGES);
        // 最近更改与日志
        map.put("最近更改", SpecialPageCategories.CHANGES);
        map.put("最新页面", SpecialPageCategories.CHANGES);
        map.put("相关更改", SpecialPageCategories.CHANGES);
        map.put("日志", SpecialPageCategories.CHANGES);
        // 媒体文件报告与上传
        map.put("文件列表", SpecialPageCategories.MEDIA);
        map.put("文件", SpecialPageCategories.MEDIA);
        map.put("已删除文件", SpecialPageCategories.MEDIA);
        map.put("上传文件", SpecialPageCategories.MEDIA);
        // 数据与工具
        map.put("版本", SpecialPageCategories.WIKI);
        // 页面工具
        map.put("页面信息", SpecialPageCategories.PAGETOOLS);
        map.put("链入页面", SpecialPageCategories.PAGETOOLS);
        map.put("移动页面", SpecialPageCategories.PAGETOOLS);
        map.put("保护页面", SpecialPageCategories.PAGETOOLS);
        map.put("创建页面", SpecialPageCategories.PAGETOOLS);
        // 重定向特殊页面
        map.put("随机页面", SpecialPageCategories.REDIRECTS);
        // 其他特殊页面
        map.put("特殊页面", SpecialPageCategories.OTHER);
        return map;
    }
}
