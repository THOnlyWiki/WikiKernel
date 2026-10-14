package cc.thonly.wikikernel.common.extension;

/**
 * {@link SetupStep#apply} 的配置写入上下文：
 * 插件 tab 写入的内容会合并进生成的 LocalSettings.yaml（互不覆盖）。
 */
public interface SetupContext {

    /**
     * 写入全局配置项（必须是 {@code wikikernel.*} 路径，如
     * {@code putGlobal("wikikernel.skins.default-skin", "plain")}）。
     */
    void putGlobal(String path, Object value);

    /** 写入当前插件的私有配置（{@code wikikernel.plugins.settings.<插件id>.<key>}）。 */
    void putPluginSetting(String key, Object value);
}
