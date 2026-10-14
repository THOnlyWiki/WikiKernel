package cc.thonly.wikikernel.common.service;

import java.util.Optional;

/**
 * 页面正文缓存桥（由 core 提供实现，插件可 {@code @Autowired} 注入）：
 * 缓存「页面渲染后的正文 HTML」，命中时直接拿渲染结果，不再重复走模板展开 / 解析器函数 / 渲染钩子。
 * <p>
 * <b>版本安全</b>：条目与页面当前 {@code latestRevisionId} 绑定。页面一保存就是新版本，
 * 旧条目<b>永远不会</b>被返回（{@link #html(String)} 会比对页面当前版本，不一致按未命中处理）。
 * <p>
 * <b>后端</b>：配置了 Redis（{@code spring.data.redis.*} 或 {@code wikikernel.cache.page.redis.enabled=true}）
 * 且能连上时用 Redis，否则退回<b>进程内 LRU</b>（按条数有界）；两者都不可用时
 * {@link #enabled()} 返回 {@code false}，此时其余方法都是安全的空操作，功能照常工作。
 * <p>
 * <b>配置</b>：站点设置 {@code cache.page.*}（{@code 特殊:权限配置 → 站点设置}）优先，
 * {@code LocalSettings.yaml} 的 {@code wikikernel.cache.page.*} 兜底；每次调用都重新读配置，
 * 改完<b>立即生效、无需重启</b>。
 * <p>
 * 缓存里存的是<b>渲染结果</b>：与具体用户相关的部分（段落编辑链接等）由 core 在命中后另行注入，
 * 所以插件可以放心读取 {@link #html(String)}。
 */
public interface PageCache {

    /** 缓存当前是否可用（开关打开且后端可用）；{@code false} 时其余方法都是空操作。 */
    boolean enabled();

    /**
     * 读取页面的正文 HTML。
     *
     * @param title 页面标题（内部会做标题规范化；大小写 / 下划线差异不影响命中）
     * @return 命中且条目版本仍是页面最新版本时返回 HTML；未命中 / 缓存关闭 / 版本过期返回空
     */
    Optional<String> html(String title);

    /**
     * 写入页面的正文 HTML（自行渲染后主动放进来；一般不需要调用，core 渲染时会自动写）。
     *
     * @param title      页面标题
     * @param html       渲染后的正文 HTML
     * @param revisionId 该 HTML 对应的版本号（页面当前 {@code latestRevisionId}）
     */
    void put(String title, String html, long revisionId);

    /** 失效单个页面（页面被编辑 / 删除 / 移动后调用；不存在时忽略）。 */
    void invalidate(String title);

    /** 失效全部页面（Redis 后端用「版本纪元」让旧条目自然过期，不做全表扫描）。 */
    void invalidateAll();

    /**
     * 失效所有<b>嵌入</b>了该模板的页面；开启 {@code cache.page.preload-on-template-change} 时
     * 顺带用虚拟线程批量重新渲染预热（失败只记日志，不影响调用方）。
     *
     * @param templateTitle 被修改的模板标题（{@code 模板:X} 与裸名 {@code X} 都可以）
     */
    void invalidateTranscluding(String templateTitle);
}
