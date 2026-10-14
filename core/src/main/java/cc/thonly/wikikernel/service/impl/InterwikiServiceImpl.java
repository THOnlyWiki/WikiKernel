package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiInterwiki;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiInterwikiMapper;
import cc.thonly.wikikernel.service.InterwikiService;
import cc.thonly.wikikernel.web.WikiUrls;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 跨 Wiki 前缀实现：表很小（通常几十行），整表缓存在内存里，{@code find()} 不查库；
 * 任何写操作后让缓存失效（下次读重建），因此同一进程内始终看到最新数据。
 */
@Service
public class InterwikiServiceImpl implements InterwikiService {

    private static final Logger log = LoggerFactory.getLogger(InterwikiServiceImpl.class);

    /** 前缀允许的字符（不含空白 / 冒号 / 竖线 / 方括号 / 花括号 / 井号 / 尖括号 / $）。 */
    private static final Pattern PREFIX = Pattern.compile("[^\\s:|\\[\\]{}$#<>]{1,32}");

    /** 语言代码：字母开头，后跟字母 / 数字 / 连字符。 */
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z][A-Za-z0-9-]{0,15}");

    /** 内置语言名（zh / en 两套；未知语言码回退为大写）。 */
    private static final Map<String, String[]> LANGUAGE_NAMES = languageNames();

    private final WikiInterwikiMapper mapper;

    /** 整表缓存（key = 小写前缀）；写操作后置空，下次读重建。 */
    private volatile Map<String, WikiInterwiki> cache;

    public InterwikiServiceImpl(WikiInterwikiMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<WikiInterwiki> all() {
        return mapper.selectList(Wrappers.<WikiInterwiki>lambdaQuery()
                .orderByAsc(WikiInterwiki::getPrefix));
    }

    @Override
    public List<WikiInterwiki> languages() {
        return all().stream()
                .filter(row -> !Strings.isBlank(row.getLanguage()))
                .sorted((left, right) -> Strings.trimToEmpty(left.getLanguage())
                        .compareToIgnoreCase(Strings.trimToEmpty(right.getLanguage())))
                .toList();
    }

    @Override
    public Optional<Target> find(String prefix) {
        String key = normalizePrefix(prefix);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        WikiInterwiki row = snapshot().get(key);
        return row == null ? Optional.empty() : Optional.of(toTarget(row));
    }

    @Override
    public WikiInterwiki save(Long id, Form form, WikiUser actor) {
        String prefix = Strings.trimToEmpty(form.prefix());
        String url = Strings.trimToEmpty(form.url());
        String language = Strings.trimToEmpty(form.language());
        validate(prefix, url, language);

        WikiInterwiki existing = id == null ? null : mapper.selectById(id);
        Optional<WikiInterwiki> clash = findByPrefix(prefix);
        if (clash.isPresent() && (existing == null || !clash.get().getId().equals(existing.getId()))) {
            throw new IllegalArgumentException("interwiki.error.duplicate");
        }

        WikiInterwiki row = existing == null ? new WikiInterwiki() : existing;
        row.setPrefix(prefix);
        row.setUrl(url);
        row.setLanguage(language.isEmpty() ? null : language);
        row.setLocal(form.local());
        row.setTranscludable(form.transcludable());
        if (existing == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        invalidate();
        log.info("跨 Wiki 前缀已保存：{} → {}（{}）", prefix, url,
                actor == null ? "系统" : actor.getUsername());
        return row;
    }

    @Override
    public boolean delete(Long id) {
        if (id == null) {
            return false;
        }
        boolean removed = mapper.deleteById(id) > 0;
        if (removed) {
            invalidate();
        }
        return removed;
    }

    @Override
    public String exportText() {
        StringBuilder out = new StringBuilder();
        for (WikiInterwiki row : all()) {
            out.append(row.getPrefix()).append('\t').append(row.getUrl()).append('\t')
                    .append(Boolean.TRUE.equals(row.getLocal()) ? 1 : 0).append('\t')
                    .append(Boolean.TRUE.equals(row.getTranscludable()) ? 1 : 0).append('\t')
                    .append(Strings.trimToEmpty(row.getLanguage()))
                    .append(System.lineSeparator());
        }
        return out.toString();
    }

    @Override
    public int importText(String text) {
        int count = 0;
        for (String raw : Strings.trimToEmpty(text).split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // 每行：前缀 <TAB 或空格> URL [本地 0/1] [可嵌入 0/1] [语言码]（后三项可省略）
            String[] parts = line.split("[\\t ]+");
            if (parts.length < 2) {
                continue;
            }
            String prefix = parts[0].trim();
            String url = parts[1].trim();
            boolean local = parts.length > 2 && "1".equals(parts[2].trim());
            boolean transcludable = parts.length > 3 && "1".equals(parts[3].trim());
            String language = parts.length > 4 ? parts[4].trim() : "";
            try {
                Long id = findByPrefix(prefix).map(WikiInterwiki::getId).orElse(null);
                save(id, new Form(prefix, url, language, local, transcludable), null);
                count++;
            } catch (IllegalArgumentException exception) {
                log.warn("跳过无效的跨 Wiki 行：{}（{}）", line, exception.getMessage());
            }
        }
        return count;
    }

    @Override
    public String languageName(String code) {
        String key = Strings.trimToEmpty(code).toLowerCase(Locale.ROOT);
        String[] names = LANGUAGE_NAMES.get(key);
        if (names == null) {
            return key.toUpperCase(Locale.ROOT);
        }
        boolean english = "en".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage());
        return english ? names[1] : names[0];
    }

    /** 把标题按 URL 段编码（空格写成下划线，保留子页面斜杠）——跨 Wiki 的 {@code $1} 用它。 */
    public static String encodeTitle(String title) {
        return WikiUrls.encodeTitle(title);
    }

    // ==================== 内部 ====================

    private void validate(String prefix, String url, String language) {
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("interwiki.error.prefix");
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://")) || url.length() > 512) {
            throw new IllegalArgumentException("interwiki.error.url");
        }
        if (url.indexOf('"') >= 0 || url.indexOf('<') >= 0 || url.indexOf('>') >= 0
                || url.indexOf('\'') >= 0 || url.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("interwiki.error.url");
        }
        if (!language.isEmpty() && !LANGUAGE.matcher(language).matches()) {
            throw new IllegalArgumentException("interwiki.error.language");
        }
    }

    private Optional<WikiInterwiki> findByPrefix(String prefix) {
        String key = normalizePrefix(prefix);
        return key.isEmpty() ? Optional.empty() : Optional.ofNullable(snapshot().get(key));
    }

    private static String normalizePrefix(String prefix) {
        return Strings.trimToEmpty(prefix).toLowerCase(Locale.ROOT);
    }

    /**
     * 读缓存（整表）。缓存为空时每次都重新读库：空表通常只是「种子还没写入 / 被外部脚本清空」，
     * 这样能自愈，避免把一次「空的快照」永久缓存下来。
     */
    private Map<String, WikiInterwiki> snapshot() {
        Map<String, WikiInterwiki> local = cache;
        if (local == null || local.isEmpty()) {
            local = load();
            cache = local;
        }
        return local;
    }

    private synchronized Map<String, WikiInterwiki> load() {
        Map<String, WikiInterwiki> map = new LinkedHashMap<>();
        for (WikiInterwiki row : all()) {
            map.put(normalizePrefix(row.getPrefix()), row);
        }
        return map;
    }

    private void invalidate() {
        cache = null;
    }

    private static Target toTarget(WikiInterwiki row) {
        return new Target(row.getPrefix(), row.getUrl(), row.getLanguage(),
                Boolean.TRUE.equals(row.getLocal()), Boolean.TRUE.equals(row.getTranscludable()));
    }

    /** 常见语言：{@code code → {中文名, English name}}。 */
    private static Map<String, String[]> languageNames() {
        Map<String, String[]> names = new LinkedHashMap<>();
        names.put("zh", new String[]{"中文", "Chinese"});
        names.put("en", new String[]{"英语", "English"});
        names.put("ja", new String[]{"日语", "Japanese"});
        names.put("ko", new String[]{"韩语", "Korean"});
        names.put("de", new String[]{"德语", "German"});
        names.put("fr", new String[]{"法语", "French"});
        names.put("es", new String[]{"西班牙语", "Spanish"});
        names.put("ru", new String[]{"俄语", "Russian"});
        names.put("it", new String[]{"意大利语", "Italian"});
        names.put("pt", new String[]{"葡萄牙语", "Portuguese"});
        names.put("ar", new String[]{"阿拉伯语", "Arabic"});
        names.put("hi", new String[]{"印地语", "Hindi"});
        names.put("vi", new String[]{"越南语", "Vietnamese"});
        names.put("th", new String[]{"泰语", "Thai"});
        names.put("id", new String[]{"印度尼西亚语", "Indonesian"});
        names.put("nl", new String[]{"荷兰语", "Dutch"});
        names.put("pl", new String[]{"波兰语", "Polish"});
        names.put("tr", new String[]{"土耳其语", "Turkish"});
        names.put("uk", new String[]{"乌克兰语", "Ukrainian"});
        names.put("fa", new String[]{"波斯语", "Persian"});
        names.put("eo", new String[]{"世界语", "Esperanto"});
        names.put("sv", new String[]{"瑞典语", "Swedish"});
        names.put("cs", new String[]{"捷克语", "Czech"});
        names.put("el", new String[]{"希腊语", "Greek"});
        names.put("he", new String[]{"希伯来语", "Hebrew"});
        names.put("hu", new String[]{"匈牙利语", "Hungarian"});
        names.put("ro", new String[]{"罗马尼亚语", "Romanian"});
        names.put("fi", new String[]{"芬兰语", "Finnish"});
        names.put("da", new String[]{"丹麦语", "Danish"});
        names.put("no", new String[]{"挪威语", "Norwegian"});
        names.put("ca", new String[]{"加泰罗尼亚语", "Catalan"});
        return Map.copyOf(names);
    }
}
