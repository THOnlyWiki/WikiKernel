package cc.thonly.wikikernel.plugin.fingerprintlogin.service;

import cc.thonly.wikikernel.plugin.fingerprintlogin.data.WikiFingerprint;

import java.util.List;
import java.util.Optional;

/**
 * 浏览器指纹存储（插件 fingerprint-login 自己的表 {@code wiki_fingerprint}）。
 * <p>
 * 实现是插件子容器里的 {@link FingerprintStoreJdbc}（走 {@code common.service.PluginDatabase}
 * 裸 SQL）；表结构由 {@code FingerprintLoginPlugin.schemaEntities()} 声明、core 的 Auto-DDL
 * 建表 / 补列。方法签名与迁移前的 {@code common.service.FingerprintStore} 完全一致。
 * <p>
 * 权限约定（由实现保证）：{@link #remove} 只能删自己的指纹；{@link #byUser} 只返回自己的；
 * 每用户最多 {@link #MAX_PER_USER} 个。
 */
public interface FingerprintStore {

    /** 每个用户最多可以登记的指纹数量。 */
    int MAX_PER_USER = 10;

    /** 某个用户的全部指纹（按登记时间倒序，最新的在前）。 */
    List<WikiFingerprint> byUser(long userId);

    /** 按指纹摘要查（登录时用；不存在返回空）。 */
    Optional<WikiFingerprint> byHash(String hash);

    /** 某个用户当前的指纹数量（上限判断用）。 */
    int countByUser(long userId);

    /**
     * 登记一个指纹。
     * <ul>
     *     <li>同一摘要已属于该用户 → 更新 label / userAgent 并返回（幂等，方便换名字）；</li>
     *     <li>同一摘要已属于**别的**用户 → 抛 {@link IllegalArgumentException}（避免抢注别人的指纹）；</li>
     *     <li>该用户已有 {@link #MAX_PER_USER} 个 → 抛 {@link IllegalStateException}。</li>
     * </ul>
     *
     * @return 落库后的指纹（含自增 id 与 createdAt）
     */
    WikiFingerprint add(long userId, String label, String hash, String userAgent);

    /** 删除自己的某个指纹（别人的 / 不存在的返回 false）。 */
    boolean remove(long userId, long id);

    /** 记录一次成功登录（更新 {@code lastUsedAt}）。 */
    void touch(long id);
}
