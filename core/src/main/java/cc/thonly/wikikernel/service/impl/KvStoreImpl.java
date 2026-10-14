package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiKv;
import cc.thonly.wikikernel.common.service.KvStore;
import cc.thonly.wikikernel.common.service.KvValue;
import cc.thonly.wikikernel.db.mapper.WikiKvMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 插件通用键值存储（{@code wiki_kv}）：面向插件的接口是 {@code common.service.KvStore}。
 * <p>
 * 值统一按文本落库（{@code value} 是长文本列），{@code type} 标记怎么解释它；
 * JSON 用 core 里注入的 Jackson 3 {@link ObjectMapper} 序列化 / 解析
 * （与应用其余部分共用同一个 mapper，全局的 JSON 配置一致）。
 * <p>
 * <b>并发处理（取舍）：</b>自增用「读-改-写」，并用<b>实例级 {@code synchronized}</b> 串行化写入。
 * 本项目按单实例部署，这样足够且不依赖任何数据库方言；<b>多实例部署</b>时两个进程可能互相覆盖
 * （要严格正确得改成 {@code UPDATE ... SET value = value + ?} 这类数据库原子更新，或者给宿主加分布式锁）。
 * {@code name} 列有唯一约束，并发创建同一个键时靠「唯一键冲突 → 重读再更新」兜底，不会写出两行。
 * <p>
 * 表由 core 的 Auto-DDL 建（见 {@code MybatisPlusAutoDdl}）；表被删掉 / 库不可用时
 * 抛 {@link IllegalStateException}（调用方看到的仍然是插件的常规异常路径，不会静默丢数据）。
 */
@Service
public class KvStoreImpl implements KvStore {

    private final WikiKvMapper mapper;
    private final ObjectMapper objectMapper;

    public KvStoreImpl(WikiKvMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    // ==================== 读 ====================

    @Override
    public Optional<KvValue> get(String name) {
        String key = requireName(name);
        return selectRow(key).map(KvStoreImpl::toValue);
    }

    @Override
    public String getString(String name, String defaultValue) {
        String key = requireName(name);
        return selectRow(key).map(WikiKv::getValue).orElse(defaultValue);
    }

    @Override
    public Optional<String> string(String name) {
        String key = requireName(name);
        String value = selectRow(key).map(WikiKv::getValue).orElse(null);
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    @Override
    public double getNumber(String name, double defaultValue) {
        String key = requireName(name);
        String raw = selectRow(key).map(WikiKv::getValue).orElse(null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public OptionalLong number(String name) {
        String key = requireName(name);
        String raw = selectRow(key).map(WikiKv::getValue).orElse(null);
        if (raw == null || raw.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of((long) Double.parseDouble(raw.trim()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public Optional<JsonNode> json(String name) {
        String key = requireName(name);
        WikiKv row = selectRow(key).orElse(null);
        if (row == null || !WikiKv.TYPE_JSON.equalsIgnoreCase(row.getType())) {
            return Optional.empty();
        }
        return parseJson(row.getValue());
    }

    @Override
    public List<KvValue> list(String namePrefix, int limit) {
        String prefix = namePrefix == null ? "" : namePrefix.trim();
        List<WikiKv> rows = prefix.isEmpty()
                ? selectAll()
                : selectByPrefix(prefix);
        List<KvValue> values = new ArrayList<>(rows.size());
        for (WikiKv row : rows) {
            values.add(toValue(row));
        }
        // 按 name 升序（与数据库排序规则无关，Java 侧统一排一遍）
        values.sort(Comparator.comparing(KvValue::name));
        if (limit > 0 && values.size() > limit) {
            return List.copyOf(values.subList(0, limit));
        }
        return List.copyOf(values);
    }

    @Override
    public boolean exists(String name) {
        String key = requireName(name);
        return selectRow(key).isPresent();
    }

    // ==================== 写 ====================

    @Override
    public void putString(String name, String value) {
        if (value == null) {
            throw new IllegalArgumentException("KvStore 的值不能为 null（要清掉某个键请用 delete(name)）");
        }
        put(requireName(name), WikiKv.TYPE_STRING, value);
    }

    @Override
    public void putNumber(String name, double value) {
        put(requireName(name), WikiKv.TYPE_NUMBER, String.valueOf(value));
    }

    @Override
    public void putJson(String name, JsonNode value) {
        if (value == null) {
            throw new IllegalArgumentException("KvStore 的 JSON 值不能为 null（要清掉某个键请用 delete(name)）");
        }
        // 先深拷贝再序列化：调用方之后改自己的节点不影响已存的值
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(value.deepCopy());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("KvStore 的 JSON 值无法序列化：" + e.getMessage(), e);
        }
        put(requireName(name), WikiKv.TYPE_JSON, serialized);
    }

    @Override
    public boolean delete(String name) {
        String key = requireName(name);
        try {
            return mapper.delete(Wrappers.<WikiKv>lambdaQuery().eq(WikiKv::getName, key)) > 0;
        } catch (RuntimeException e) {
            throw databaseError("delete", e);
        }
    }

    @Override
    public synchronized long increment(String name, long delta) {
        String key = requireName(name);
        WikiKv row = selectRow(key).orElse(null);
        long base = 0L;
        if (row != null) {
            if (WikiKv.TYPE_JSON.equalsIgnoreCase(row.getType())) {
                throw new IllegalArgumentException("键 " + key + " 是 JSON 类型，不能自增");
            }
            String raw = row.getValue() == null ? "" : row.getValue().trim();
            if (!raw.isEmpty()) {
                try {
                    base = (long) Double.parseDouble(raw);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("键 " + key + " 的值不是数字：" + row.getValue(), e);
                }
            }
        }
        long next = base + delta;
        put(key, WikiKv.TYPE_NUMBER, String.valueOf((double) next));
        return next;
    }

    // ==================== 工具 ====================

    /** 校验并规范化键名（Auto-DDL 的 {@code name} 是 VARCHAR(191) 唯一键）。 */
    static String requireName(String name) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("KvStore 的键名不能为空");
        }
        if (key.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "KvStore 的键名过长（" + key.length() + " > " + MAX_NAME_LENGTH + "）：" + key);
        }
        return key;
    }

    /** 写入一行（存在则覆盖，并刷新 updateAt；并发下靠唯一键兜底）。 */
    private void put(String key, String type, String value) {
        synchronized (this) {
            WikiKv row = selectRow(key).orElse(null);
            if (row == null) {
                WikiKv created = new WikiKv();
                created.setName(key);
                created.setType(type);
                created.setValue(value);
                created.setUpdateAt(LocalDateTime.now());
                try {
                    mapper.insert(created);
                    return;
                } catch (RuntimeException race) {
                    // 唯一键冲突（并发创建同一个键）：退回「重读 + 更新」
                    row = selectRow(key).orElse(null);
                    if (row == null) {
                        throw databaseError("insert", race);
                    }
                }
            }
            row.setType(type);
            row.setValue(value);
            row.setUpdateAt(LocalDateTime.now());
            try {
                mapper.updateById(row);
            } catch (RuntimeException e) {
                throw databaseError("update", e);
            }
        }
    }

    private Optional<WikiKv> selectRow(String key) {
        try {
            return Optional.ofNullable(mapper.selectOne(Wrappers.<WikiKv>lambdaQuery()
                    .eq(WikiKv::getName, key)
                    .last("LIMIT 1")));
        } catch (RuntimeException e) {
            throw databaseError("select", e);
        }
    }

    private List<WikiKv> selectAll() {
        try {
            return mapper.selectList(Wrappers.<WikiKv>lambdaQuery());
        } catch (RuntimeException e) {
            throw databaseError("list", e);
        }
    }

    private List<WikiKv> selectByPrefix(String prefix) {
        try {
            return mapper.selectList(Wrappers.<WikiKv>lambdaQuery().likeRight(WikiKv::getName, prefix));
        } catch (RuntimeException e) {
            throw databaseError("list", e);
        }
    }

    private Optional<JsonNode> parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readTree(raw));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static KvValue toValue(WikiKv row) {
        Long id = row.getId();
        return new KvValue(id == null ? 0L : id, row.getName(), row.getType(), row.getValue());
    }

    private static IllegalStateException databaseError(String action, RuntimeException cause) {
        return new IllegalStateException(
                "KvStore 访问 wiki_kv 失败（" + action + "；表应由 Auto-DDL 自动创建）：" + cause.getMessage(), cause);
    }
}
