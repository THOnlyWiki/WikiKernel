package cc.thonly.wikikernel.service;

/**
 * **测试替身**（只在 plugin/dep-demo 的测试源里）：
 * 插件不能依赖 core，因此 {@code DepDemoAopHook} 用类名字符串匹配 core 的
 * {@code cc.thonly.wikikernel.service.SiteSettingsService}；这里用**同名 FQN** 的接口验证匹配规则
 * （与 core 里的真实接口不在同一个类路径 / 工程里，不会冲突）。
 */
public interface SiteSettingsService {

    String get(String key, String defaultValue);

    boolean getBoolean(String key, boolean defaultValue);
}
