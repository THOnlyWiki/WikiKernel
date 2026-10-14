package cc.thonly.wikikernel.common.plugin.support;

import cc.thonly.wikikernel.common.extension.MessageBundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 资源式插件消息包：插件的本地化文案放在**插件自己的 jar 里**，不再集中到 core 的
 * {@code messages.properties}，也不再写成 Java 里的 {@code Map} 字面量：
 *
 * <pre>
 * src/main/resources/i18n/messages.properties       ← 默认语言（源语言，中文 zh）
 * src/main/resources/i18n/messages_en.properties    ← 英文
 * src/main/resources/i18n/messages_ja.properties    ← 其它语言（可选，按需加）
 * </pre>
 *
 * 用法（插件里只要一行）：
 *
 * <pre>
 * public class XxxMessages extends ResourceMessageBundle {
 * }
 * </pre>
 *
 * 需要别的语言时覆写 {@link #languages()}（默认已覆盖 {@code en} / {@code zh-CN} / {@code zh-TW} /
 * {@code ja} / {@code ko} / {@code fr} / {@code de} / {@code ru} / {@code es}；文件不存在就跳过）。
 * <p>
 * 缺失的键**不会**回落到中文，也不会抛异常：由 core 的消息源统一回退成翻译键本身
 * （见 {@code PluginMessageSource}），便于一眼看出漏翻。
 */
public abstract class ResourceMessageBundle implements MessageBundle {

    /** 默认资源基名（插件 jar 内的路径，不含 {@code .properties}）。 */
    public static final String DEFAULT_BASENAME = "i18n/messages";

    /** 默认语言（{@code messages.properties} 的内容）。 */
    public static final String DEFAULT_LANGUAGE = "zh";

    private final String basename;
    private volatile Map<String, Map<String, String>> cache;

    protected ResourceMessageBundle() {
        this(DEFAULT_BASENAME);
    }

    protected ResourceMessageBundle(String basename) {
        this.basename = basename == null || basename.isBlank() ? DEFAULT_BASENAME : basename.trim();
    }

    /** 该插件提供的语言（键为语言标签，值为文件名后缀；默认语言见 {@link #DEFAULT_LANGUAGE}）。 */
    protected List<String> languages() {
        return List.of("en", "zh-CN", "zh-TW", "ja", "ko", "fr", "de", "ru", "es");
    }

    @Override
    public Map<String, Map<String, String>> messages() {
        Map<String, Map<String, String>> local = this.cache;
        if (local == null) {
            synchronized (this) {
                local = this.cache;
                if (local == null) {
                    local = Collections.unmodifiableMap(load());
                    this.cache = local;
                }
            }
        }
        return local;
    }

    private Map<String, Map<String, String>> load() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        Map<String, String> defaults = read(basename + ".properties");
        if (!defaults.isEmpty()) {
            result.put(DEFAULT_LANGUAGE, defaults);
        }
        for (String language : languages()) {
            if (language == null || language.isBlank() || DEFAULT_LANGUAGE.equals(language)) {
                continue;
            }
            Map<String, String> bundle = read(basename + "_" + language.trim() + ".properties");
            if (!bundle.isEmpty()) {
                result.put(language.trim(), bundle);
            }
        }
        return result;
    }

    /** 从**插件自己的**类路径读一个 properties 文件（读不到返回空表）。 */
    private Map<String, String> read(String resource) {
        ClassLoader loader = getClass().getClassLoader();
        try (InputStream stream = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource)) {
            if (stream == null) {
                return Map.of();
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            Map<String, String> result = new LinkedHashMap<>();
            List<String> names = new ArrayList<>(properties.stringPropertyNames());
            Collections.sort(names);
            for (String name : names) {
                result.put(name, properties.getProperty(name, ""));
            }
            return Collections.unmodifiableMap(result);
        } catch (IOException exception) {
            return Map.of();
        }
    }
}
