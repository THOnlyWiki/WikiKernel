package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiPageProperty;
import cc.thonly.wikikernel.common.service.PagePropertyStore;
import cc.thonly.wikikernel.common.service.PagePropertyValue;
import cc.thonly.wikikernel.db.mapper.WikiPagePropertyMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 页面属性存储（{@code wiki_page_property}）：
 * core 的渲染产出属性与插件数据（如浏览计数）都走这里。
 * 对插件暴露的接口是 {@code common.service.PagePropertyStore}。
 */
@Service
public class PagePropertyStoreImpl implements PagePropertyStore {

    /** 持久属性名：不会被 {@link #replaceRendered} 清除（插件启动时登记）。 */
    private static final Set<String> DURABLE = ConcurrentHashMap.newKeySet();

    private final WikiPagePropertyMapper mapper;

    public PagePropertyStoreImpl(WikiPagePropertyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<String> get(long pageId, String name) {
        return select(pageId, name).map(WikiPageProperty::getPropValue);
    }

    @Override
    public Map<Long, Map<String, String>> batch(Collection<Long> pageIds, Collection<String> names) {
        if (pageIds == null || pageIds.isEmpty() || names == null || names.isEmpty()) {
            return Map.of();
        }
        List<WikiPageProperty> rows = mapper.selectList(Wrappers.<WikiPageProperty>lambdaQuery()
                .in(WikiPageProperty::getPageId, pageIds)
                .in(WikiPageProperty::getPropName, names));
        return group(rows);
    }

    @Override
    public Map<Long, Map<String, String>> all(Collection<Long> pageIds) {
        if (pageIds == null || pageIds.isEmpty()) {
            return Map.of();
        }
        List<WikiPageProperty> rows = mapper.selectList(Wrappers.<WikiPageProperty>lambdaQuery()
                .in(WikiPageProperty::getPageId, pageIds));
        return group(rows);
    }

    @Override
    public void set(long pageId, String name, String value) {
        upsert(pageId, normalize(name), value == null ? "" : value);
    }

    @Override
    public void remove(long pageId, String name) {
        mapper.delete(Wrappers.<WikiPageProperty>lambdaQuery()
                .eq(WikiPageProperty::getPageId, pageId)
                .eq(WikiPageProperty::getPropName, normalize(name)));
    }

    @Override
    public void removeAll(long pageId) {
        mapper.delete(Wrappers.<WikiPageProperty>lambdaQuery()
                .eq(WikiPageProperty::getPageId, pageId));
    }

    @Override
    public void replaceRendered(long pageId, Map<String, String> rendered) {
        Map<String, String> values = rendered == null ? Map.of() : rendered;
        List<WikiPageProperty> existing = mapper.selectList(Wrappers.<WikiPageProperty>lambdaQuery()
                .eq(WikiPageProperty::getPageId, pageId));
        for (WikiPageProperty row : existing) {
            String name = row.getPropName();
            if (!DURABLE.contains(name) && !values.containsKey(name)) {
                mapper.deleteById(row.getId());
            }
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            upsert(pageId, entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
    }

    @Override
    public void registerDurable(String name) {
        String value = normalize(name);
        if (!value.isEmpty()) {
            DURABLE.add(value);
        }
    }

    @Override
    public long addToInt(long pageId, String name, long delta) {
        String key = normalize(name);
        WikiPageProperty row = select(pageId, key).orElse(null);
        if (row == null) {
            WikiPageProperty created = new WikiPageProperty();
            created.setPageId(pageId);
            created.setPropName(key);
            created.setPropValue(String.valueOf(delta));
            try {
                mapper.insert(created);
                return delta;
            } catch (RuntimeException race) {
                // 并发下唯一键冲突：退回「读 + 更新」
                row = select(pageId, key).orElse(null);
                if (row == null) {
                    throw race;
                }
            }
        }
        long value = parseLong(row.getPropValue()) + delta;
        row.setPropValue(String.valueOf(value));
        mapper.updateById(row);
        return value;
    }

    @Override
    public long sumInt(String name) {
        long total = 0;
        for (WikiPageProperty row : rowsOf(name)) {
            total += parseLong(row.getPropValue());
        }
        return total;
    }

    @Override
    public List<PagePropertyValue> listByIntDesc(String name, int limit) {
        List<PagePropertyValue> values = new ArrayList<>();
        for (WikiPageProperty row : rowsOf(name)) {
            values.add(new PagePropertyValue(row.getPageId(), parseLong(row.getPropValue())));
        }
        values.sort(Comparator.comparingLong(PagePropertyValue::value).reversed());
        if (limit > 0 && values.size() > limit) {
            return List.copyOf(values.subList(0, limit));
        }
        return List.copyOf(values);
    }

    // ==================== 工具 ====================

    private Optional<WikiPageProperty> select(long pageId, String name) {
        String key = normalize(name);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(Wrappers.<WikiPageProperty>lambdaQuery()
                .eq(WikiPageProperty::getPageId, pageId)
                .eq(WikiPageProperty::getPropName, key)
                .last("LIMIT 1")));
    }

    private List<WikiPageProperty> rowsOf(String name) {
        String key = normalize(name);
        if (key.isEmpty()) {
            return List.of();
        }
        return mapper.selectList(Wrappers.<WikiPageProperty>lambdaQuery()
                .eq(WikiPageProperty::getPropName, key));
    }

    private void upsert(long pageId, String name, String value) {
        WikiPageProperty row = select(pageId, name).orElse(null);
        if (row == null) {
            WikiPageProperty created = new WikiPageProperty();
            created.setPageId(pageId);
            created.setPropName(name);
            created.setPropValue(value);
            mapper.insert(created);
            return;
        }
        row.setPropValue(value);
        mapper.updateById(row);
    }

    private static Map<Long, Map<String, String>> group(List<WikiPageProperty> rows) {
        Map<Long, Map<String, String>> result = new LinkedHashMap<>();
        for (WikiPageProperty row : rows) {
            result.computeIfAbsent(row.getPageId(), key -> new LinkedHashMap<>())
                    .put(row.getPropName(), row.getPropValue() == null ? "" : row.getPropValue());
        }
        return result;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim();
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
