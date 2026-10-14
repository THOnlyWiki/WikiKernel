package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 日志类型扩展点（日志类型注册中心）。
 * <p>
 * 新增日志类型 = 新增一个 core Bean，或插件里
 * {@code PluginContext.register(LogType.class, ...)}；
 * 展示名可通过消息键 {@code log.typeName.<id>} 本地化（见 LogTypeRegistry），
 * 也可以覆盖 {@link #displayName()} 自己提供。
 */
public interface LogType extends Extension {

    /** 类型 id（存库、过滤用），如 create / edit / delete / register / rights。 */
    String id();

    /** 展示名（可本地化）；默认与 {@link #id()} 相同。 */
    default String displayName() {
        return id();
    }

    /** 目标类型：页面 / 用户 / 纯文本（决定日志页里目标怎么展示）。 */
    default TargetKind targetKind() {
        return TargetKind.TEXT;
    }

    /** 过滤链接排序（越小越靠前）。 */
    default int order() {
        return 100;
    }

    /**
     * 日志**详情**的展示文案（可选；仿 MediaWiki 日志的 {@code LogFormatter} 参数解析）。
     * <p>
     * core 只把存库的 {@code detail} 原样交给类型自己（如审核的 {@code edit|审核意见}），
     * 本地化 / 结构化解析都由插件负责（通常查 {@code MessageBundle} 里的键）。
     * 返回 {@code null} / 空串表示「按 core 的通用规则展示」（原始 detail 文本）。
     */
    default String detailText(String detail) {
        return null;
    }

    enum TargetKind {
        /** 目标是页面标题。 */
        PAGE,
        /** 目标是用户。 */
        USER,
        /** 目标是文件（{@code wiki_file.name}，查看权限按 {@code 文件:<name>} 判定）。 */
        FILE,
        /** 纯文本目标。 */
        TEXT
    }
}
