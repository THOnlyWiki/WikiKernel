package cc.thonly.wikikernel.common.service;

import cc.thonly.wikikernel.common.data.WikiKv;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 插件通用键值存储桥（由 core 提供实现，插件可直接 {@code @Autowired} 注入）。
 * <p>
 * 对应表 {@code wiki_kv}（由 core 的 Auto-DDL 建表），抽象结构 {@code (id, name, type, value)}：
 * {@code name} 是唯一键，{@code value} 统一按长文本存，{@code type} 说明怎么解释它
 * （{@link WikiKv#TYPE_STRING} / {@link WikiKv#TYPE_NUMBER} / {@link WikiKv#TYPE_JSON}）。
 * （值列的物理列名是 {@code kv_value}，因为 H2 把 {@code VALUE} 当保留字；见 {@link WikiKv} 的类注释。）
 * <p>
 * <b>用途约定：插件用它存自己的小配置 / 小状态</b>（开关、游标、计数、缓存的 JSON 片段……），
 * <b>不要拿它当业务表</b>：
 * <ul>
 *     <li>它是给插件私有数据兜底的通用 KV，没有事务语义、没有历史版本、没有按值查询；</li>
 *     <li>复杂的业务数据请自己建表 + Auto-DDL（实体放 {@code common.data}），
 *     或复用已有桥（页面 {@code PageQueryStore}、页面属性 {@code PagePropertyStore} 等）；</li>
 *     <li>单行体积有限（{@code value} 是长文本），不要往里塞文件 / 大列表；</li>
 *     <li>键名建议加插件前缀（如 {@code comments.last-sweep}），避免不同插件撞键。</li>
 * </ul>
 * <b>JSON 类型说明：</b>这里的「JsonElement」用 Jackson 3（Spring Boot 4 自带）的
 * {@link tools.jackson.databind.JsonNode} 表示，方法签名里的 {@code JsonNode} 都是它；
 * 写进去的节点会被<b>深拷贝</b>后序列化存储，调用方之后改动自己的节点不影响已存的值。
 * <p>
 * <b>键名与异常：</b>{@code name} 为空 / 全空白 / 超过 {@value #MAX_NAME_LENGTH} 字符时抛
 * {@link IllegalArgumentException}；{@code increment} 遇到非数值类型同样抛
 * {@link IllegalArgumentException}；数据库访问出错（例如表被删掉）抛 {@link IllegalStateException}。
 * 空值语义：{@link #putString(String, String)} 的 {@code value}、{@link #putJson(String, JsonNode)}
 * 的 {@code value} 不接受 {@code null}（要「没有这个值」请用 {@link #delete(String)}）。
 */
public interface KvStore {

    /** 键名最大长度（{@code wiki_kv.name} 是 VARCHAR(191) 唯一键）。 */
    int MAX_NAME_LENGTH = 191;

    /**
     * 按名字读一条记录（带 type 与原始文本值）。
     *
     * @param name 键
     * @return 记录；键不存在时为 {@link Optional#empty()}
     */
    Optional<KvValue> get(String name);

    /**
     * 读字符串（便捷方法）。
     *
     * @param name         键
     * @param defaultValue 键不存在时返回的默认值（可以是 {@code null}）
     */
    String getString(String name, String defaultValue);

    /**
     * 读字符串：键不存在 / 值是空串时为 {@link Optional#empty()}。
     * <p>
     * 与「存了空串」不好区分——要区分就用 {@link #get(String)} 或 {@link #getString(String, String)}。
     */
    Optional<String> string(String name);

    /**
     * 读数值（便捷方法；按文本解析，且不限类型：字符串类型里存了 {@code "42"} 也能读出来）。
     *
     * @param name         键
     * @param defaultValue 键不存在 / 解析不出数值时返回的默认值
     */
    double getNumber(String name, double defaultValue);

    /**
     * 读数值（整数）：键不存在 / 解析不出数值时为 {@link OptionalLong#empty()}。
     * <p>
     * 小数会被截断（如存 {@code 3.5} 读回 {@code 3}）；要保留小数用 {@link #getNumber(String, double)}。
     */
    OptionalLong number(String name);

    /**
     * 读 JSON：键不存在时为空；值不是 JSON 类型（或内容坏掉）时也返回空。
     *
     * @param name 键
     * @return Jackson 3 的 {@link JsonNode}（语义上等价于 Gson 的 {@code JsonElement}）
     */
    Optional<JsonNode> json(String name);

    /**
     * 写字符串（存在则覆盖，同时刷新 {@code updateAt}）。
     *
     * @param name  键
     * @param value 值（不能为 {@code null}）
     */
    void putString(String name, String value);

    /**
     * 写数值（存在则覆盖，同时刷新 {@code updateAt}）。
     * <p>
     * 存储形式是 {@code double} 的文本（{@code 42.0}、{@code 3.5}），
     * 所以 {@link #number(String)} 读回 {@code 42}，{@link #getNumber(String, double)} 读回 {@code 42.0}；
     * {@link #increment(String, long)} 按整数读-改-写，小数部分会被截断（{@code 42.5} + 1 → {@code 43}）。
     */
    void putNumber(String name, double value);

    /**
     * 写 JSON（存在则覆盖，同时刷新 {@code updateAt}）。
     * <p>
     * 存的是序列化后的文本（{@code type=json}）；节点会先深拷贝，调用方后续改动不影响已存的值。
     *
     * @param name  键
     * @param value JSON 节点（不能为 {@code null}）
     */
    void putJson(String name, JsonNode value);

    /**
     * 删除一个键。
     *
     * @return 真的删掉了一行时返回 {@code true}，键不存在返回 {@code false}
     */
    boolean delete(String name);

    /** 键是否存在。 */
    boolean exists(String name);

    /**
     * 按键名前缀列举（按 {@code name} 升序）。
     *
     * @param namePrefix 键名前缀（空 / {@code null} = 全部）
     * @param limit      最多返回多少条（{@code <= 0} = 不限制）
     */
    List<KvValue> list(String namePrefix, int limit);

    /**
     * 数值自增（键不存在时从 0 开始），返回自增后的新值，并刷新 {@code updateAt}。
     * <p>
     * 自增后的类型固定为 {@link WikiKv#TYPE_NUMBER}（原来存的是字符串 {@code "41"} 也会被改写）。
     *
     * @param name  键
     * @param delta 增量（可为负）
     * @return 自增后的新值
     * @throws IllegalArgumentException 键上已存在非数值类型的值（{@code type=json}、
     *                                  或字符串不是合法数字）
     */
    long increment(String name, long delta);
}
