package cc.thonly.wikikernel.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 保证中英文文案 key 一致，且模板里引用的 key 都有定义（本地化不遗漏）。 */
class MessageBundleTest {

    /** 模板目录：优先取 Gradle 传入的绝对路径（测试沙箱下工作目录不再是模块目录），IDE 里回退相对路径。 */
    private static final Path TEMPLATES = Path.of(System.getProperty("wk.templates.dir",
            "src/main/resources/templates"));
    private static final Pattern TEMPLATE_KEY = Pattern.compile("#\\{([A-Za-z0-9_.]+)");
    private static final Pattern MESSAGES_UTIL_KEY = Pattern.compile("#messages\\.\\w+\\(\\s*'([A-Za-z0-9_.]+)'");

    @Test
    void englishBundleHasExactlyTheSameKeys() {
        assertEquals(keys("zh"), keys("en"));
    }

    @Test
    void everyKeyUsedInTemplatesExists() {
        Set<String> defined = keys("zh");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".html")).toList()) {
                String content = Files.readString(file);
                collectMissing(TEMPLATE_KEY, content, defined, file, missing);
                collectMissing(MESSAGES_UTIL_KEY, content, defined, file, missing);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(missing.isEmpty(), () -> "模板引用了未定义的文案 key: " + missing);
    }

    private static void collectMissing(Pattern pattern, String content, Set<String> defined,
                                       Path file, List<String> missing) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            if (!defined.contains(matcher.group(1))) {
                missing.add(file.getFileName() + " -> " + matcher.group(1));
            }
        }
    }

    private static Set<String> keys(String language) {
        return new TreeSet<>(ResourceBundle.getBundle("messages", Locale.forLanguageTag(language)).keySet());
    }
}
