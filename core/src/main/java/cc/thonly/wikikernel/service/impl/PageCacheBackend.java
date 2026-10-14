package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.service.PageBody;

import java.time.Duration;
import java.util.Optional;

/**
 * 页面正文缓存的后端存储（进程内 LRU / Redis）：只负责按键存取，
 * 配置读取、版本校验、失效与预渲染编排都在 {@link PageCacheServiceImpl} 里。
 * <p>
 * 实现约定：<b>故障只抛运行时异常</b>，由调用方决定降级（缓存不可用不能影响页面渲染）。
 */
interface PageCacheBackend {

    /** 后端名（{@code memory} / {@code redis}），用于日志与后端选择。 */
    String name();

    /** 读取条目（不存在 / 已过期返回空）。 */
    Optional<PageBody> get(String key);

    /** 写入条目并按 {@code ttl} 过期。 */
    void put(String key, PageBody body, Duration ttl);

    /** 删除单个条目（不存在时忽略）。 */
    void remove(String key);

    /** 让全部条目失效（实现方式各自决定；Redis 用版本纪元，不做 KEYS / SCAN 全表扫描）。 */
    void clear();

    /** 探测后端是否可用（Redis 会真的连一次；进程内后端恒为 true）。 */
    boolean available();
}
