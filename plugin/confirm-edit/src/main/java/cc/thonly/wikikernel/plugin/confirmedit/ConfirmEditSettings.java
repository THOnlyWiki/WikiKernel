package cc.thonly.wikikernel.plugin.confirmedit;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.util.Strings;

import java.util.Arrays;
import java.util.List;

/**
 * ConfirmEdit 的站点设置读取（键与 core 的站点设置 / LocalSettings.yaml 完全一致）：
 * <ul>
 *     <li>{@code features.confirm-edit}：功能开关（默认关闭）；</li>
 *     <li>{@code features.confirm-edit.bypass-groups}：免验证用户组（{@code |} 或换行分隔，
 *     默认 {@link #DEFAULT_BYPASS_GROUPS}）。</li>
 * </ul>
 * 设置表单由插件自己注册（站点设置分区 + 安装向导步骤；键沿用迁移前的
 * {@code features.confirm-edit.*}），这里只读同一批键，所以开关语义在迁移前后一致。
 */
public class ConfirmEditSettings {

    /** 站点设置键：功能开关（与 core 的 {@code FeatureService.CONFIRM_EDIT_KEY} 相同）。 */
    public static final String ENABLED_KEY = "features.confirm-edit";

    /** 站点设置键：免验证用户组（与 core 的 {@code ConfirmEditService.BYPASS_GROUPS_KEY} 相同）。 */
    public static final String BYPASS_GROUPS_KEY = "features.confirm-edit.bypass-groups";

    /** 默认免验证组（core 侧同名常量；站点设置留空时用它）。 */
    public static final String DEFAULT_BYPASS_GROUPS = "管理员|行政员|自动确定用户|机器人";

    private final SiteConfig siteConfig;

    public ConfirmEditSettings(SiteConfig siteConfig) {
        this.siteConfig = siteConfig;
    }

    /** 功能开关（默认关闭）。 */
    public boolean enabled() {
        return siteConfig != null && siteConfig.getBoolean(ENABLED_KEY, false);
    }

    /** 免验证用户组。 */
    public List<String> bypassGroups() {
        return split(siteConfig == null ? "" : siteConfig.get(BYPASS_GROUPS_KEY, DEFAULT_BYPASS_GROUPS));
    }

    /** 该用户是否需要验证码（功能关闭或命中免验证组时不需要；**匿名用户需要**）。 */
    public boolean appliesTo(Viewer viewer) {
        if (!enabled()) {
            return false;
        }
        if (viewer == null || !viewer.loggedIn()) {
            return true;
        }
        List<String> bypass = bypassGroups();
        return viewer.groups().stream().noneMatch(bypass::contains);
    }

    /** {@code |} 或换行分隔 → 列表（去空白、去空项）。 */
    static List<String> split(String value) {
        return Arrays.stream(Strings.trimToEmpty(value).split("[|\\r\\n]+"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }
}
