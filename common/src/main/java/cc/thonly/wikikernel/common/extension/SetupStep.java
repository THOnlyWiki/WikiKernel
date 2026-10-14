package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

import java.util.List;

/**
 * 安装向导里插件的设置入口（仿 MediaWiki 安装器的扩展设置，一个插件 = 一个 tab）。
 * <p>
 * 插件在 {@code onStart} 里通过 {@code context.register(SetupStep.class, ...)} 注册；
 * core 的 {@code /init} 会列出所有已加载插件（没有注册 SetupStep 的插件也会有一个只读 tab），
 * 插件 tab 只有在**核心设置完成后**才能提交，且各插件写入的配置互不覆盖（合并进同一份 LocalSettings.yaml）。
 */
public interface SetupStep extends Extension {

    /** tab 稳定 id（通常与插件 id 相同）。 */
    String id();

    /** tab 显示名（本地化名，可直接返回中文或英文）。 */
    String displayName();

    /** tab 里的说明文字（可空）。 */
    default String description() {
        return "";
    }

    /** 排序（小的在前；0-99 建议留给核心）。 */
    default int order() {
        return 100;
    }

    /** 表单字段（为空 = 该插件 tab 仅展示信息）。 */
    default List<SetupField> fields() {
        return List.of();
    }

    /**
     * 执行初始化：把表单值写入配置（通过 {@link SetupContext}）。
     * 不建议在这里直接写数据库——配置在向导结束时写入 YAML 并重启后生效。
     */
    SetupResult apply(java.util.Map<String, String> values, SetupContext context);
}
